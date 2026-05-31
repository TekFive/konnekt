package org.tekfive.konnekt.message.sms.providers.twilio

import okhttp3.Request
import okio.Buffer
import org.tekfive.konnekt.message.sms.providers.twilio.model.TwilioSmsSendRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TwilioSmsClientTest {

    @Test
    fun `twilio sms auth uses basic auth with account sid and token`() {
        val auth = TwilioSmsAuth(
            accountSid = "AC123",
            authToken = "secret",
            baseUrl = "https://api.twilio.com",
        )

        assertEquals("Basic " + authHeaderValue("AC123", "secret"), auth.authorizationHeader)
        assertEquals("https://api.twilio.com", auth.normalizedBaseUrl)
    }

    @Test
    fun `twilio sms client posts message with twilio account path and form body`() {
        var capturedRequest: Request? = null
        val client = TwilioSmsClient(
            auth = TwilioSmsAuth(
                accountSid = "AC123",
                authToken = "secret",
                baseUrl = null,
            ),
            executeOverride = { request ->
                capturedRequest = request
                TwilioSmsClient.TwilioSmsRawResponse(
                    code = 201,
                    body = """{"sid":"SM123","status":"queued"}""",
                    headers = emptyMap(),
                )
            },
        )

        val response = client.send(
            TwilioSmsSendRequest(
                to = "+15555550100",
                from = "+15555550999",
                body = "SMS body",
            ),
        )

        assertEquals("SM123", response.sid)
        assertEquals("queued", response.status)
        assertNotNull(capturedRequest)
        assertEquals("Basic " + authHeaderValue("AC123", "secret"), capturedRequest!!.header("Authorization"))
        assertEquals("/2010-04-01/Accounts/AC123/Messages.json", capturedRequest!!.url.encodedPath)
        assertTrue(
            capturedRequest!!.body?.contentType().toString().startsWith("application/x-www-form-urlencoded"),
        )
        assertEquals(
            "To=%2B15555550100&From=%2B15555550999&Body=SMS%20body",
            bodyToString(capturedRequest!!),
        )
    }

    @Test
    fun `twilio sms client fetches status by message sid`() {
        var capturedRequest: Request? = null
        val client = TwilioSmsClient(
            auth = TwilioSmsAuth(
                accountSid = "AC123",
                authToken = "secret",
                baseUrl = "https://example.test",
            ),
            executeOverride = { request ->
                capturedRequest = request
                TwilioSmsClient.TwilioSmsRawResponse(
                    code = 200,
                    body = """{"sid":"SM123","status":"delivered"}""",
                    headers = emptyMap(),
                )
            },
        )

        val response = client.getMessageStatus("SM123")

        assertEquals("SM123", response?.sid)
        assertEquals("delivered", response?.status)
        assertNotNull(capturedRequest)
        assertEquals("/2010-04-01/Accounts/AC123/Messages/SM123.json", capturedRequest!!.url.encodedPath)
        assertEquals("Basic " + authHeaderValue("AC123", "secret"), capturedRequest!!.header("Authorization"))
    }

    @Test
    fun `twilio sms client posts messaging service sid when configured`() {
        var capturedRequest: Request? = null
        val client = TwilioSmsClient(
            auth = TwilioSmsAuth(
                accountSid = "AC123",
                authToken = "secret",
                baseUrl = null,
            ),
            executeOverride = { request ->
                capturedRequest = request
                TwilioSmsClient.TwilioSmsRawResponse(
                    code = 201,
                    body = """{"sid":"SM123","status":"queued"}""",
                    headers = emptyMap(),
                )
            },
        )

        client.send(
            TwilioSmsSendRequest(
                to = "+15555550100",
                messagingServiceSid = "MG123",
                body = "SMS body",
            ),
        )

        assertEquals(
            "To=%2B15555550100&Body=SMS%20body&MessagingServiceSid=MG123",
            bodyToString(capturedRequest!!),
        )
    }

    @Test
    fun `twilio sms client rejects successful response without sid`() {
        val client = TwilioSmsClient(
            auth = TwilioSmsAuth(
                accountSid = "AC123",
                authToken = "secret",
                baseUrl = null,
            ),
            executeOverride = {
                TwilioSmsClient.TwilioSmsRawResponse(
                    code = 201,
                    body = """{"status":"queued"}""",
                    headers = emptyMap(),
                )
            },
        )

        val exception = assertFailsWith<TwilioSmsException> {
            client.send(
                TwilioSmsSendRequest(
                    to = "+15555550100",
                    from = "+15555550999",
                    body = "SMS body",
                ),
            )
        }

        assertEquals("Twilio SMS send succeeded without returning a message SID", exception.message)
    }

    private fun authHeaderValue(accountSid: String, authToken: String): String {
        return java.util.Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray(Charsets.UTF_8))
    }

    private fun bodyToString(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }
}
