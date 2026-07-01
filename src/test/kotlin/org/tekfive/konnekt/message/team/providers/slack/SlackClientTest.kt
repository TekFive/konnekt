package org.tekfive.konnekt.message.team.providers.slack

import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import okio.Buffer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SlackClientTest {

    @Test
    fun `slack auth builds bearer header and defaults base url`() {
        val auth = SlackAuth("xoxb-token", null)

        assertEquals("Bearer xoxb-token", auth.authorizationHeader)
        assertEquals("https://slack.com", auth.normalizedBaseUrl)
    }

    @Test
    fun `slack auth toString does not expose the bot token`() {
        val auth = SlackAuth("xoxb-super-secret", null)

        assertFalse(auth.toString().contains("xoxb-super-secret"))
    }

    @Test
    fun `post message uses chat postMessage endpoint and bearer auth`() {
        var capturedRequest: Request? = null
        val client = SlackClient(
            auth = SlackAuth("xoxb-token", "https://example.test"),
            executeOverride = { request ->
                capturedRequest = request
                """{"ok":true,"channel":"C123","ts":"1710000000.000100"}"""
            },
        )

        val response = client.postMessage("C123", "hello")

        assertEquals("/api/chat.postMessage", capturedRequest!!.url.encodedPath)
        assertEquals("Bearer xoxb-token", capturedRequest!!.header("Authorization"))
        assertEquals("""{"channel":"C123","text":"hello"}""", bodyToString(capturedRequest!!))
        assertEquals("C123:1710000000.000100", response.messageId)
    }

    @Test
    fun `lookup user by email returns null when slack reports users not found`() {
        val client = SlackClient(
            auth = SlackAuth("xoxb-token", "https://example.test"),
            executeOverride = {
                """{"ok":false,"error":"users_not_found"}"""
            },
        )

        assertEquals(null, client.lookupUserByEmail("missing@example.com"))
    }

    @Test
    fun `find conversation by name follows pagination`() {
        val responses = ArrayDeque(
            listOf(
                """{"ok":true,"channels":[{"id":"C111","name":"first"}],"response_metadata":{"next_cursor":"next"}}""",
                """{"ok":true,"channels":[{"id":"C222","name":"care-team"}],"response_metadata":{"next_cursor":""}}""",
            )
        )
        val client = SlackClient(
            auth = SlackAuth("xoxb-token", "https://example.test"),
            executeOverride = {
                responses.removeFirst()
            },
        )

        assertEquals("C222", client.findConversationByName("#care-team"))
    }

    @Test
    fun `find conversation by name matches case-insensitively`() {
        val client = SlackClient(
            auth = SlackAuth("xoxb-token", "https://example.test"),
            executeOverride = {
                """{"ok":true,"channels":[{"id":"C222","name":"care-team"}]}"""
            },
        )

        assertEquals("C222", client.findConversationByName("#Care-Team"))
    }

    @Test
    fun `slack api error raises slack exception with the error token`() {
        val client = SlackClient(
            auth = SlackAuth("xoxb-token", "https://example.test"),
            executeOverride = {
                """{"ok":false,"error":"invalid_auth"}"""
            },
        )

        val exception = assertFailsWith<SlackException> {
            client.postMessage("C123", "hello")
        }

        assertEquals("Slack request failed: invalid_auth", exception.message)
    }

    @Test
    fun `missing ok field is treated as an error not a success`() {
        val client = SlackClient(
            auth = SlackAuth("xoxb-token", "https://example.test"),
            executeOverride = {
                """{"channel":"C123","ts":"1710000000.000100"}"""
            },
        )

        assertFailsWith<SlackException> {
            client.postMessage("C123", "hello")
        }
    }

    @Test
    fun `http failure exposes the status code but never the response body`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/api/chat.postMessage") { exchange ->
                val bytes = """{"detail":"SENSITIVE-BODY-CONTENT"}""".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(503, bytes.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(bytes)
                }
                exchange.close()
            }
            server.start()

            val client = SlackClient(SlackAuth("xoxb-token", "http://127.0.0.1:${server.address.port}"))

            val exception = assertFailsWith<SlackException> {
                client.postMessage("C123", "hello")
            }

            assertEquals(503, exception.statusCode)
            assertFalse(exception.message!!.contains("SENSITIVE-BODY-CONTENT"))
        } finally {
            server.stop(0)
        }
    }

    private fun bodyToString(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }
}
