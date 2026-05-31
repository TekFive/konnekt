package org.tekfive.konnekt.message.email.providers.twilio

import com.sun.net.httpserver.HttpServer
import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.email.EmailMessage
import org.tekfive.konnekt.message.email.EmailCapability
import org.tekfive.konnekt.message.email.EmailStatus
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridEmailEvent
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TwilioSendGridEmailSenderTest {

    @Test
    fun `sendgrid sender maps opened activity to opened email status`() {
        val status = TwilioEmailProvider.mapStatus(
            listOf(TwilioSendGridEmailEvent(event_name = "open")),
        )

        assertEquals(EmailStatus.OPENED, status)
    }

    @Test
    fun `sendgrid sender advertises status lookup capability`() {
        assertEquals(setOf(EmailCapability.STATUS_LOOKUP), TwilioEmailProvider.capabilities)
    }

    @Test
    fun `sendgrid sender returns queued status for accepted send response`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/v3/mail/send") { exchange ->
                exchange.responseHeaders.add("X-Message-Id", "msg-123")
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
            server.start()

            val response = TwilioEmailProvider.send(
                message = testMessage(),
                providerConfiguration = json {
                    "apiKey" set "SG.test"
                    "baseUrl" set "http://127.0.0.1:${server.address.port}"
                },
            )

            assertEquals("msg-123", response.messageId)
            assertEquals(EmailStatus.QUEUED, response.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sendgrid sender normalizes plain text content type and preserves html`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val requestBodies = mutableListOf<String>()
        try {
            server.createContext("/v3/mail/send") { exchange ->
                requestBodies.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                exchange.responseHeaders.add("X-Message-Id", "msg-123")
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
            server.start()

            val config = json {
                "apiKey" set "SG.test"
                "baseUrl" set "http://127.0.0.1:${server.address.port}"
            }

            TwilioEmailProvider.send(
                message = testMessage(contentType = EmailMessage.TEXT_CONTENT_TYPE),
                providerConfiguration = config,
            )
            TwilioEmailProvider.send(
                message = testMessage(contentType = EmailMessage.HTML_CONTENT_TYPE),
                providerConfiguration = config,
            )

            assertEquals(2, requestBodies.size)
            assertEquals(true, requestBodies[0].contains("\"type\":\"text/plain\""))
            assertEquals(true, requestBodies[1].contains("\"type\":\"text/html\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sendgrid sender uses send tracking id for activity lookup`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val capturedQuery = AtomicReference<String?>()
        try {
            server.createContext("/v3/mail/send") { exchange ->
                exchange.responseHeaders.add("X-Message-Id", "msg-123")
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
            server.createContext("/v3/messages") { exchange ->
                capturedQuery.set(exchange.requestURI.rawQuery ?: exchange.requestURI.query)
                respondJson(
                    exchange = exchange,
                    statusCode = 200,
                    body = """{"messages":[{"msg_id":"msg-123","status":"delivered","events":[{"event_name":"delivered","timestamp":"123"}]}]}""",
                )
            }
            server.start()

            val config = json {
                "apiKey" set "SG.test"
                "baseUrl" set "http://127.0.0.1:${server.address.port}"
            }

            val sendResponse = TwilioEmailProvider.send(
                message = testMessage(),
                providerConfiguration = config,
            )
            val status = TwilioEmailProvider.status(sendResponse.messageId, config)

            assertEquals("msg-123", sendResponse.messageId)
            assertEquals(EmailStatus.DELIVERED, status)
            assertEquals("query=msg_id%20LIKE%20%27msg-123%25%27", capturedQuery.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sendgrid sender returns null when activity lookup is forbidden`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/v3/messages") { exchange ->
                respondJson(
                    exchange = exchange,
                    statusCode = 403,
                    body = """{"errors":[{"message":"Email Activity access is required"}]}""",
                )
            }
            server.start()

            val status = TwilioEmailProvider.status(
                messageId = "msg-123",
                providerConfiguration = json {
                    "apiKey" set "SG.test"
                    "baseUrl" set "http://127.0.0.1:${server.address.port}"
                },
            )

            assertNull(status)
        } finally {
            server.stop(0)
        }
    }

    private fun testMessage(contentType: String = EmailMessage.TEXT_CONTENT_TYPE): EmailMessage {
        return EmailMessage(
            to = listOf(MessageRecipient("to@example.com", "To")),
            from = MessageAddress("from@example.com", "From"),
            subject = "Subject",
            body = "Body",
            contentType = contentType,
        )
    }

    private fun respondJson(exchange: com.sun.net.httpserver.HttpExchange, statusCode: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
        exchange.close()
    }
}
