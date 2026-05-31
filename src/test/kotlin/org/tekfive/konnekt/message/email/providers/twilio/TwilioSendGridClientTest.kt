package org.tekfive.konnekt.message.email.providers.twilio

import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridContent
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridEmailAddress
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridEmailEvent
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridMailSendRequest
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridPersonalization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import okhttp3.Request

class TwilioSendGridClientTest {

    @Test
    fun `sendgrid client builds bearer auth and defaults base url`() {
        val auth = TwilioSendGridConfiguration(
            apiKey = "SG.test",
            baseUrl = null,
        )

        assertEquals("Bearer SG.test", auth.authorizationHeader)
        assertEquals("https://api.sendgrid.com", auth.normalizedBaseUrl)
    }

    @Test
    fun `sendgrid client extracts message id from send response header`() {
        val client = TwilioSendGridClient(
            auth = TwilioSendGridConfiguration(apiKey = "SG.test"),
            executeOverride = {
                TwilioSendGridClient.TwilioSendGridRawResponse(
                    code = 202,
                    body = "",
                    headers = mapOf("X-Message-Id" to "msg-123"),
                )
            },
        )

        val response = client.sendMail(testRequest())

        assertEquals("msg-123", response.messageId)
        assertEquals("queued", response.status)
    }

    @Test
    fun `sendgrid client extracts message id from send response body`() {
        val client = TwilioSendGridClient(
            auth = TwilioSendGridConfiguration(apiKey = "SG.test"),
            executeOverride = {
                TwilioSendGridClient.TwilioSendGridRawResponse(
                    code = 202,
                    body = """{"message_id":"msg-456"}""",
                    headers = emptyMap(),
                )
            },
        )

        val response = client.sendMail(testRequest())

        assertEquals("msg-456", response.messageId)
        assertEquals("queued", response.status)
    }

    @Test
    fun `sendgrid client parses activity response event name`() {
        val client = TwilioSendGridClient(
            auth = TwilioSendGridConfiguration(apiKey = "SG.test"),
            executeOverride = {
                TwilioSendGridClient.TwilioSendGridRawResponse(
                    code = 200,
                    body = """{"msg_id":"msg-123","events":[{"event_name":"delivered","timestamp":"123"}]}""",
                    headers = emptyMap(),
                )
            },
        )

        val response = client.getEmailActivity("msg-123")

        assertEquals("msg-123", response?.messageId)
        assertEquals(listOf(TwilioSendGridEmailEvent(event_name = "delivered", timestamp = "123")), response?.events)
    }

    @Test
    fun `sendgrid client queries activity feed by x message id`() {
        var capturedRequest: Request? = null
        val trackingId = "Ua9z9lSTSaqYBWJe_Xfc-Q"
        val client = TwilioSendGridClient(
            auth = TwilioSendGridConfiguration(apiKey = "SG.test"),
            executeOverride = { request ->
                capturedRequest = request
                TwilioSendGridClient.TwilioSendGridRawResponse(
                    code = 200,
                    body = """{"messages":[{"msg_id":"msg-123","status":"delivered","events":[{"event_name":"opened","timestamp":"123"}]}]}""",
                    headers = emptyMap(),
                )
            },
        )

        val response = client.getEmailActivity(trackingId)

        assertNotNull(capturedRequest)
        assertEquals("/v3/messages", capturedRequest!!.url.encodedPath)
        assertEquals("msg_id LIKE '$trackingId%'", capturedRequest!!.url.queryParameter("query"))
        assertEquals("msg-123", response?.messageId)
        assertEquals("delivered", response?.status)
        assertEquals(listOf(TwilioSendGridEmailEvent(event_name = "opened", timestamp = "123")), response?.events)
    }

    @Test
    fun `sendgrid client prefers exact activity match over prefix matches`() {
        val trackingId = "Ua9z9lSTSaqYBWJe_Xfc-Q"
        val client = TwilioSendGridClient(
            auth = TwilioSendGridConfiguration(apiKey = "SG.test"),
            executeOverride = {
                TwilioSendGridClient.TwilioSendGridRawResponse(
                    code = 200,
                    body = """
                        {"messages":[
                          {"msg_id":"$trackingId-01","status":"sent","events":[{"event_name":"opened","timestamp":"111"}]},
                          {"message_id":"$trackingId","status":"delivered","events":[{"event_name":"delivered","timestamp":"222"}]}
                        ]}
                    """.trimIndent(),
                    headers = emptyMap(),
                )
            },
        )

        val response = client.getEmailActivity(trackingId)

        assertEquals(trackingId, response?.messageId)
        assertEquals("delivered", response?.status)
        assertEquals(listOf(TwilioSendGridEmailEvent(event_name = "delivered", timestamp = "222")), response?.events)
    }

    @Test
    fun `sendgrid client prefers longest matching activity prefix when multiple rows match`() {
        val trackingId = "Ua9z9lSTSaqYBWJe_Xfc-Q"
        val client = TwilioSendGridClient(
            auth = TwilioSendGridConfiguration(apiKey = "SG.test"),
            executeOverride = {
                TwilioSendGridClient.TwilioSendGridRawResponse(
                    code = 200,
                    body = """
                        {"messages":[
                          {"msg_id":"$trackingId-1","status":"sent","events":[{"event_name":"opened","timestamp":"111"}]},
                          {"message_id":"$trackingId-12345","status":"delivered","events":[{"event_name":"delivered","timestamp":"222"}]},
                          {"id":"$trackingId-12","status":"queued","events":[{"event_name":"processed","timestamp":"333"}]}
                        ]}
                    """.trimIndent(),
                    headers = emptyMap(),
                )
            },
        )

        val response = client.getEmailActivity(trackingId)

        assertEquals("$trackingId-12345", response?.messageId)
        assertEquals("delivered", response?.status)
        assertEquals(listOf(TwilioSendGridEmailEvent(event_name = "delivered", timestamp = "222")), response?.events)
    }

    @Test
    fun `sendgrid client returns null when activity lookup is forbidden`() {
        val client = TwilioSendGridClient(
            auth = TwilioSendGridConfiguration(apiKey = "SG.test"),
            executeOverride = {
                TwilioSendGridClient.TwilioSendGridRawResponse(
                    code = 403,
                    body = """{"errors":[{"message":"Email Activity access is required"}]}""",
                    headers = emptyMap(),
                )
            },
        )

        assertNull(client.getEmailActivity("msg-123"))
    }

    @Test
    fun `sendgrid client returns null when activity lookup is not found`() {
        val client = TwilioSendGridClient(
            auth = TwilioSendGridConfiguration(apiKey = "SG.test"),
            executeOverride = {
                TwilioSendGridClient.TwilioSendGridRawResponse(
                    code = 404,
                    body = """{"errors":[{"message":"not found"}]}""",
                    headers = emptyMap(),
                )
            },
        )

        assertNull(client.getEmailActivity("msg-123"))
    }

    private fun testRequest(): TwilioSendGridMailSendRequest {
        return TwilioSendGridMailSendRequest(
            personalizations = listOf(
                TwilioSendGridPersonalization(
                    to = listOf(TwilioSendGridEmailAddress(email = "to@example.com")),
                ),
            ),
            from = TwilioSendGridEmailAddress(email = "from@example.com"),
            subject = "Subject",
            content = listOf(TwilioSendGridContent(type = "text/plain", value = "Body")),
        )
    }
}
