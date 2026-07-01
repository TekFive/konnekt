package org.tekfive.konnekt.message.team.providers.slack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.team.TeamMessage
import org.tekfive.konnekt.message.team.TeamMessageCapability
import org.tekfive.konnekt.message.team.TeamMessageException
import org.tekfive.konnekt.message.team.TeamMessageStatus
import org.tekfive.konnekt.message.team.senders.SlackSender
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SlackSenderTest {

    @Test
    fun `sender reports no optional capabilities`() {
        assertEquals(emptySet<TeamMessageCapability>(), SlackSender.capabilities)
    }

    @Test
    fun `status reports unknown because slack has no status lookup`() {
        val status = SlackSender.status("C123:1710000000.000100", json { "botToken" set "xoxb-test" })

        assertEquals(TeamMessageStatus.UNKNOWN, status)
    }

    @Test
    fun `send fails fast without posting when any recipient is unresolved`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val messagePosted = AtomicBoolean(false)
        try {
            server.createContext("/api/conversations.list") { exchange ->
                respondJson(exchange, """{"ok":true,"channels":[{"id":"C123","name":"care-team"}]}""")
            }
            server.createContext("/api/chat.postMessage") { exchange ->
                messagePosted.set(true)
                respondJson(exchange, """{"ok":true,"channel":"C123","ts":"1710000000.000100"}""")
            }
            server.start()

            val config = json {
                "botToken" set "xoxb-test"
                "baseUrl" set "http://127.0.0.1:${server.address.port}"
            }

            val exception = assertFailsWith<TeamMessageException> {
                SlackSender.send(
                    TeamMessage(
                        to = listOf(MessageRecipient("#care-team"), MessageRecipient("#missing-channel")),
                        from = MessageAddress("system", "System"),
                        body = "Body",
                    ),
                    config,
                )
            }

            // Counts only — the unresolved address must never appear in the message.
            assertEquals("Slack could not resolve 1 of 2 recipients", exception.message)
            assertFalse(messagePosted.get())
        } finally {
            server.stop(0)
        }
    }

    private fun respondJson(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
        exchange.close()
    }
}
