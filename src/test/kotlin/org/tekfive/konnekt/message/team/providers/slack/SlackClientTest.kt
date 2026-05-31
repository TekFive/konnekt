package org.tekfive.konnekt.message.team.providers.slack

import okhttp3.Request
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlackClientTest {

    @Test
    fun `slack auth builds bearer header and defaults base url`() {
        val auth = SlackAuth("xoxb-token", null)

        assertEquals("Bearer xoxb-token", auth.authorizationHeader)
        assertEquals("https://slack.com", auth.normalizedBaseUrl)
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
    fun `slack api error raises slack exception`() {
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

    private fun bodyToString(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }
}
