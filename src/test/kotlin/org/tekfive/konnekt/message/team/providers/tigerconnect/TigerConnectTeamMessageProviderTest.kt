package org.tekfive.konnekt.message.team.providers.tigerconnect

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.team.TeamMessage
import org.tekfive.konnekt.message.team.TeamMessageCapability
import org.tekfive.konnekt.message.team.TeamMessageException
import org.tekfive.konnekt.message.team.TeamMessageStatus
import org.tekfive.konnekt.message.team.senders.TigerConnectSender
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TigerConnectSenderTest {

    @Test
    fun `sender reports expected capabilities`() {
        assertEquals(
            setOf(TeamMessageCapability.PRIORITY, TeamMessageCapability.STATUS_LOOKUP),
            TigerConnectSender.capabilities,
        )
    }

    @Test
    fun `send fails fast without posting when any recipient is unresolved`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val messagePosted = AtomicBoolean(false)
        try {
            server.createContext("/users") { exchange ->
                if (exchange.requestURI.query == "email=user-1") {
                    respondJson(exchange, 200, """{"users":[{"id":"tc-user-1","email":"user-1"}]}""")
                } else {
                    respondJson(exchange, 200, """{}""")
                }
            }
            server.createContext("/groups") { exchange -> respondJson(exchange, 200, """{}""") }
            server.createContext("/roles") { exchange -> respondJson(exchange, 200, """{}""") }
            server.createContext("/distribution-lists") { exchange -> respondJson(exchange, 200, """{}""") }
            server.createContext("/message") { exchange ->
                messagePosted.set(true)
                respondJson(exchange, 200, """{"messageId":"msg-001","status":"sent"}""")
            }
            server.start()

            val exception = assertFailsWith<TeamMessageException> {
                TigerConnectSender.send(
                    TeamMessage(
                        to = listOf(MessageRecipient("user-1"), MessageRecipient("nobody-here")),
                        from = MessageAddress("system", "System"),
                        body = "Body",
                    ),
                    config(server.address.port),
                )
            }

            // Counts only — the unresolved address must never appear in the message.
            assertEquals("TigerConnect could not resolve 1 of 2 recipients", exception.message)
            assertFalse(messagePosted.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `aggregate status reports any failure and only complete delivery or reads`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/message") { exchange ->
                val id = exchange.requestURI.path.removePrefix("/message/").removeSuffix("/status")
                if (id == "m-error") {
                    respondJson(exchange, 500, """{}""")
                } else {
                    val status = when (id) {
                        "m-read", "m-read2" -> "read"
                        "m-delivered" -> "delivered"
                        "m-sent" -> "sent"
                        "m-failed" -> "failed"
                        else -> "bogus"
                    }
                    respondJson(exchange, 200, """{"messageId":"$id","status":"$status"}""")
                }
            }
            server.start()

            val config = config(server.address.port)

            // Any failed delivery wins.
            assertEquals(TeamMessageStatus.FAILED, TigerConnectSender.status("multi:m-read,m-failed", config))
            // READ only when every recipient has read.
            assertEquals(TeamMessageStatus.READ, TigerConnectSender.status("multi:m-read,m-read2", config))
            // DELIVERED when everyone is at least delivered but not all have read.
            assertEquals(TeamMessageStatus.DELIVERED, TigerConnectSender.status("multi:m-read,m-delivered", config))
            // Otherwise SENT.
            assertEquals(TeamMessageStatus.SENT, TigerConnectSender.status("multi:m-delivered,m-sent", config))
            // One failing status lookup does not abort the whole check.
            assertEquals(TeamMessageStatus.SENT, TigerConnectSender.status("multi:m-read,m-error", config))
        } finally {
            server.stop(0)
        }
    }

    private fun config(port: Int): JsonObject {
        return json {
            "apiKey" set "test-key"
            "apiSecret" set "test-secret"
            "baseUrl" set "http://127.0.0.1:$port"
        }
    }

    private fun respondJson(exchange: HttpExchange, statusCode: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
        exchange.close()
    }
}
