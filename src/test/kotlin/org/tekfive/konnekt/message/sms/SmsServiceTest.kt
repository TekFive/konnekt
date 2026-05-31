package org.tekfive.konnekt.message.sms

import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessagingException
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.sms.providers.twilio.TwilioSmsAuth
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import org.tekfive.konnekt.message.sms.providers.twilio.TwilioSmsClient
import org.tekfive.konnekt.message.sms.providers.twilio.TwilioSmsSender

class SmsServiceTest {

    @AfterTest
    fun tearDown() {
        SmsService.reset()
        TwilioSmsSender.clientFactory = { auth ->
            TwilioSmsClient(auth)
        }
    }

    @Test
    fun `send delegates to endpoint sender`() {
        val endpoint = SmsEndpoint(
            id = "test",
            provider = SmsServiceProvider.TEST,
            config = json { },
        )

        val response = SmsService.send(
            SmsMessage(
                to = listOf(MessageAddress("+15555550100", "Primary")),
                from = MessageAddress("+15555550999", "System"),
                body = "SMS body",
            ),
            endpoint,
        )

        assertEquals(SmsStatus.SENT, response.status)
    }

    @Test
    fun `status returns null when sender does not support lookup`() {
        val endpoint = SmsEndpoint(
            id = "test",
            provider = SmsServiceProvider.TEST,
            config = json { },
        )

        assertNull(SmsService.status("message-1", endpoint))
    }

    @Test
    fun `send routes twilio endpoints through twilio sender`() {
        TwilioSmsSender.clientFactory = { auth ->
            TwilioSmsClient(
                auth = auth,
                executeOverride = {
                    TwilioSmsClient.TwilioSmsRawResponse(
                        code = 201,
                        body = """{"sid":"SM123","status":"queued"}""",
                        headers = emptyMap(),
                    )
                },
            )
        }

        val endpoint = SmsEndpoint(
            id = "twilio",
            provider = SmsServiceProvider.TWILIO,
            config = json {
                "accountSid" set "AC123"
                "authToken" set "secret"
                "fromNumber" set "+15555550999"
            },
        )

        val response = SmsService.send(
            SmsMessage(
                to = listOf(MessageAddress("+15555550100", "Primary")),
                from = MessageAddress("+15555550999", "System"),
                body = "SMS body",
            ),
            endpoint,
        )

        assertEquals("twilio", response.providerId)
        assertEquals(SmsStatus.QUEUED, response.status)
    }

    @Test
    fun `queued sms send returns receipt details when tracking is requested and provider supports status lookup`() {
        TwilioSmsSender.clientFactory = { auth ->
            TwilioSmsClient(
                auth = auth,
                executeOverride = {
                    TwilioSmsClient.TwilioSmsRawResponse(
                        code = 201,
                        body = """{"sid":"SM123","status":"queued"}""",
                        headers = emptyMap(),
                    )
                },
            )
        }

        val endpoint = SmsEndpoint(
            id = "twilio-endpoint",
            provider = SmsServiceProvider.TWILIO,
            config = json {
                "accountSid" set "AC123"
                "authToken" set "secret"
                "fromNumber" set "+15555550999"
            },
        )
        SmsService.registerResolver { endpoint }

        val queuedMessage = QueuedSmsMessage(
            label = "sms",
            endpointId = endpoint.id,
            to = listOf(MessageAddress("+15555550100", "Primary")),
            from = MessageAddress("+15555550999", "System"),
            body = "Body",
            trackReceipt = true,
        ).toQueuedMessage()

        val receiptDetails = SmsService.send(queuedMessage)

        assertNotNull(receiptDetails)
        assertEquals(endpoint.id, receiptDetails.endpointId)
        assertEquals(listOf("+15555550100"), receiptDetails.recipientAddresses)
        assertEquals(endpoint.id, receiptDetails.providerTrackingData.string("endpointId"))
        assertEquals("SM123", receiptDetails.providerTrackingData.string("messageId"))
    }

    @Test
    fun `queued sms send returns no receipt details when tracking is not requested`() {
        TwilioSmsSender.clientFactory = { auth ->
            TwilioSmsClient(
                auth = auth,
                executeOverride = {
                    TwilioSmsClient.TwilioSmsRawResponse(
                        code = 201,
                        body = """{"sid":"SM123","status":"queued"}""",
                        headers = emptyMap(),
                    )
                },
            )
        }

        val endpoint = SmsEndpoint(
            id = "twilio-endpoint",
            provider = SmsServiceProvider.TWILIO,
            config = json {
                "accountSid" set "AC123"
                "authToken" set "secret"
                "fromNumber" set "+15555550999"
            },
        )
        SmsService.registerResolver { endpoint }

        val queuedMessage = QueuedSmsMessage(
            label = "sms",
            endpointId = endpoint.id,
            to = listOf(MessageAddress("+15555550100", "Primary")),
            from = MessageAddress("+15555550999", "System"),
            body = "Body",
            trackReceipt = false,
        ).toQueuedMessage()

        val receiptDetails = SmsService.send(queuedMessage)

        assertNull(receiptDetails)
    }

    @Test
    fun `send wraps provider failures as messaging exceptions`() {
        TwilioSmsSender.clientFactory = { _ ->
            object : TwilioSmsClient(TwilioSmsAuth("AC123", "secret")) {
                override fun send(requestBody: org.tekfive.konnekt.message.sms.providers.twilio.model.TwilioSmsSendRequest): org.tekfive.konnekt.message.sms.providers.twilio.model.TwilioSmsSendResponse {
                    throw org.tekfive.konnekt.message.sms.providers.twilio.TwilioSmsException("Twilio failure")
                }
            }
        }

        val endpoint = SmsEndpoint(
            id = "twilio",
            provider = SmsServiceProvider.TWILIO,
            config = json {
                "accountSid" set "AC123"
                "authToken" set "secret"
                "fromNumber" set "+15555550999"
            },
        )

        val exception = assertFailsWith<MessagingException> {
            SmsService.send(
                SmsMessage(
                    to = listOf(MessageAddress("+15555550100", "Primary")),
                    from = MessageAddress("+15555550999", "System"),
                    body = "SMS body",
                ),
                endpoint,
            )
        }

        assertEquals(false, exception.recoverable)
        assertEquals("Twilio failure", exception.message)
    }
}
