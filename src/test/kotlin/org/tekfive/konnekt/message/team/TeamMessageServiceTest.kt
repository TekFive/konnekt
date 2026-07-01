package org.tekfive.konnekt.message.team

import com.sun.net.httpserver.HttpServer
import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.MessagingException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamMessageServiceTest {

    @AfterTest
    fun cleanup() {
        TeamMessageService.reset()
    }

    @Test
    fun `send delegates to endpoint sender`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/users") { exchange ->
                respondJson(exchange, 200, """{"users":[{"id":"tc-user-1","email":"user-1"}]}""")
            }
            server.createContext("/message") { exchange ->
                respondJson(exchange, 200, """{"messageId":"msg-001","status":"sent"}""")
            }
            server.start()

            val endpoint = testEndpoint(server.address.port)

            val response = TeamMessageService.send(
                TeamMessage(
                    to = listOf(MessageRecipient("user-1", "User One")),
                    from = MessageAddress("system", "System"),
                    subject = "Subject",
                    body = "Body",
                ),
                endpoint,
            )

            assertEquals(TeamMessageStatus.SENT, response.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `send delegates to slack endpoint sender`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val capturedBody = AtomicReference<String>()
        try {
            server.createContext("/api/conversations.list") { exchange ->
                respondJson(exchange, 200, """{"ok":true,"channels":[{"id":"C123","name":"care-team"}]}""")
            }
            server.createContext("/api/chat.postMessage") { exchange ->
                capturedBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                respondJson(exchange, 200, """{"ok":true,"channel":"C123","ts":"1710000000.000100"}""")
            }
            server.start()

            val endpoint = slackEndpoint(server.address.port)

            val response = TeamMessageService.send(
                TeamMessage(
                    to = listOf(MessageRecipient("#care-team", "Care Team")),
                    from = MessageAddress("system", "System"),
                    subject = "Subject",
                    body = "Body",
                ),
                endpoint,
            )

            assertEquals(TeamMessageStatus.SENT, response.status)
            assertEquals("C123:1710000000.000100", response.messageId)
            assertEquals("""{"channel":"C123","text":"*Subject*\n\nBody"}""", capturedBody.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `status returns unknown for senders without status lookup capability`() {
        // SlackSender does not declare STATUS_LOOKUP, so no provider call is attempted.
        val endpoint = slackEndpoint(port = 1)

        assertEquals(TeamMessageStatus.UNKNOWN, TeamMessageService.status("C123:1710000000.000100", endpoint))
    }

    @Test
    fun `provider http failures are classified for retry by status code`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val messageStatusCode = AtomicInteger(503)
        try {
            server.createContext("/users") { exchange ->
                respondJson(exchange, 200, """{"users":[{"id":"tc-user-1","email":"user-1"}]}""")
            }
            server.createContext("/message") { exchange ->
                respondJson(exchange, messageStatusCode.get(), """{"detail":"SENSITIVE-BODY-CONTENT"}""")
            }
            server.start()

            val endpoint = testEndpoint(server.address.port)
            val message = TeamMessage(
                to = listOf(MessageRecipient("user-1", "User One")),
                from = MessageAddress("system", "System"),
                body = "Body",
            )

            // 503 is transient — recoverable, so the queue may retry.
            val recoverable = assertFailsWith<MessagingException> {
                TeamMessageService.send(message, endpoint)
            }
            assertTrue(recoverable.recoverable)
            assertFalse(recoverable.message!!.contains("SENSITIVE-BODY-CONTENT"))

            // 400 is a permanent failure — not recoverable.
            messageStatusCode.set(400)
            val notRecoverable = assertFailsWith<MessagingException> {
                TeamMessageService.send(message, endpoint)
            }
            assertFalse(notRecoverable.recoverable)
            assertFalse(notRecoverable.message!!.contains("SENSITIVE-BODY-CONTENT"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `queue rejects attachments the endpoint sender does not support`() {
        val endpoint = slackEndpoint(port = 1)
        TeamMessageService.registerResolver { endpointId ->
            if (endpointId == endpoint.id) endpoint else null
        }

        assertFailsWith<TeamMessageException> {
            TeamMessageService.queue(
                QueuedTeamMessage(
                    label = "label",
                    endpointId = endpoint.id,
                    to = listOf(MessageRecipient("#care-team")),
                    from = MessageAddress("system", "System"),
                    body = "Body",
                    attachments = listOf(
                        TeamMessageAttachment(
                            fileName = "report.pdf",
                            contentType = "application/pdf",
                            content = byteArrayOf(0x1),
                        )
                    ),
                )
            )
        }
    }

    private fun testEndpoint(port: Int): TeamMessageEndpoint {
        return TeamMessageEndpoint(
            id = "test",
            provider = TeamMessageServiceProvider.TIGER_CONNECT,
            config = json {
                "apiKey" set "test-key"
                "apiSecret" set "test-secret"
                "baseUrl" set "http://127.0.0.1:$port"
            },
        )
    }

    private fun slackEndpoint(port: Int): TeamMessageEndpoint {
        return TeamMessageEndpoint(
            id = "slack-test",
            provider = TeamMessageServiceProvider.SLACK,
            config = json {
                "botToken" set "xoxb-test"
                "baseUrl" set "http://127.0.0.1:$port"
            },
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
