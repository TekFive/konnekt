package org.tekfive.konnekt.message.sms.providers.twilio

import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.sms.SmsMessage
import org.tekfive.konnekt.message.sms.providers.twilio.model.TwilioSmsSendRequest
import org.tekfive.konnekt.message.sms.providers.twilio.model.TwilioSmsSendResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.tekfive.konnekt.message.sms.SmsStatus

class TwilioSmsSenderTest {

    @Test
    fun `twilio sms sender maps delivered message to delivered status`() {
        assertEquals(SmsStatus.DELIVERED, TwilioSmsSender.mapStatus("delivered"))
    }

    @Test
    fun `twilio sms sender maps whatsapp read status to delivered`() {
        assertEquals(SmsStatus.DELIVERED, TwilioSmsSender.mapStatus("read"))
    }

    @Test
    fun `twilio sms sender maps partially delivered status to sent`() {
        assertEquals(SmsStatus.SENT, TwilioSmsSender.mapStatus("partially_delivered"))
    }

    @Test
    fun `twilio sms sender leaves inbound-only statuses unmapped`() {
        assertEquals(SmsStatus.UNKNOWN, TwilioSmsSender.mapStatus("received"))
    }

    @Test
    fun `twilio sms sender rejects multiple recipients`() {
        val exception = assertFailsWith<TwilioSmsException> {
            TwilioSmsSender.send(
                SmsMessage(
                    to = listOf(
                        MessageAddress("+15555550100", "Primary"),
                        MessageAddress("+15555550101", "Backup"),
                    ),
                    from = MessageAddress("+15555550999", "System"),
                    body = "SMS body",
                ),
                json {
                    "accountSid" set "AC123"
                    "authToken" set "secret"
                },
            )
        }

        assertEquals("Twilio SMS sender supports exactly one recipient per send", exception.message)
    }

    @Test
    fun `twilio sms sender requires endpoint sender config`() {
        val exception = assertFailsWith<TwilioSmsException> {
            TwilioSmsSender.send(
                SmsMessage(
                    to = listOf(MessageAddress("+15555550100", "Primary")),
                    from = MessageAddress("+15555550999", "System"),
                    body = "SMS body",
                ),
                json {
                    "accountSid" set "AC123"
                    "authToken" set "secret"
                },
            )
        }

        assertEquals(
            "Twilio SMS send requires endpoint config to provide either messagingServiceSid or fromNumber",
            exception.message,
        )
    }

    @Test
    fun `twilio sms sender uses messaging service sid without requiring blank message from address`() {
        var capturedRequest: TwilioSmsSendRequest? = null
        val originalFactory = TwilioSmsSender.clientFactory
        TwilioSmsSender.clientFactory = { _ ->
            object : TwilioSmsClient(TwilioSmsAuth("AC123", "secret")) {
                override fun send(requestBody: TwilioSmsSendRequest): TwilioSmsSendResponse {
                    capturedRequest = requestBody
                    return TwilioSmsSendResponse(sid = "SM123", status = "queued")
                }
            }
        }

        try {
            TwilioSmsSender.send(
                SmsMessage(
                    to = listOf(MessageAddress("+15555550100", "Primary")),
                    from = MessageAddress("+15555550999", "System"),
                    body = "SMS body",
                ),
                json {
                    "accountSid" set "AC123"
                    "authToken" set "secret"
                    "messagingServiceSid" set "MG123"
                },
            )
        } finally {
            TwilioSmsSender.clientFactory = originalFactory
        }

        assertNull(capturedRequest?.from)
        assertEquals("MG123", capturedRequest?.messagingServiceSid)
    }
}
