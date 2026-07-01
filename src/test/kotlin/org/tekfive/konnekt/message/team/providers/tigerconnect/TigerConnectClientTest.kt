package org.tekfive.konnekt.message.team.providers.tigerconnect

import okhttp3.HttpUrl
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectSendRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TigerConnectClientTest {

    @Test
    fun `basic auth header is built from key and secret`() {
        val auth = TigerConnectAuth("key", "secret", "https://example.test")

        assertTrue(auth.authorizationHeader.startsWith("Basic "))
        assertEquals("https://example.test", auth.normalizedBaseUrl)
    }

    @Test
    fun `auth toString does not expose the key or secret`() {
        val auth = TigerConnectAuth("super-secret-key", "super-secret-value")

        val rendered = auth.toString()
        assertFalse(rendered.contains("super-secret-key"))
        assertFalse(rendered.contains("super-secret-value"))
    }

    @Test
    fun `send request uses message endpoint and basic auth`() {
        var capturedPath = ""
        var authHeader = ""
        val client = TigerConnectClient(
            auth = TigerConnectAuth("key", "secret", "https://example.test"),
            executeOverride = { request ->
                capturedPath = request.url.encodedPath
                authHeader = request.header("Authorization").orEmpty()
                """{"messageId":"m-1","status":"sent"}"""
            },
        )

        val response = client.sendMessage(
            TigerConnectSendRequest(
                targetType = "user",
                targetId = "u-1",
                body = "hello",
            )
        )

        assertEquals("/message", capturedPath)
        assertTrue(authHeader.startsWith("Basic "))
        assertEquals("m-1", response.resolvedMessageId)
    }

    @Test
    fun `status request uses message status endpoint`() {
        var capturedPath = ""
        val client = TigerConnectClient(
            auth = TigerConnectAuth("key", "secret", "https://example.test"),
            executeOverride = { request ->
                capturedPath = request.url.encodedPath
                """{"messageId":"m-1","status":"read"}"""
            },
        )

        val response = client.getMessageStatus("m-1")

        assertEquals("/message/m-1/status", capturedPath)
        assertEquals("read", response.status)
    }

    @Test
    fun `query parameter values are url encoded`() {
        var capturedUrl: HttpUrl? = null
        val client = TigerConnectClient(
            auth = TigerConnectAuth("key", "secret", "https://example.test"),
            executeOverride = { request ->
                capturedUrl = request.url
                """{"users":[]}"""
            },
        )

        client.findUserByEmail("first last+tag@example.com")

        // The value must round-trip through the URL intact, which requires proper encoding.
        assertEquals("first last+tag@example.com", capturedUrl!!.queryParameter("email"))
        assertFalse(capturedUrl.toString().contains(' '))
    }

    @Test
    fun `message id path segment is url encoded`() {
        var capturedUrl: HttpUrl? = null
        val client = TigerConnectClient(
            auth = TigerConnectAuth("key", "secret", "https://example.test"),
            executeOverride = { request ->
                capturedUrl = request.url
                """{"messageId":"m 1/x","status":"read"}"""
            },
        )

        client.getMessageStatus("m 1/x")

        // A slash inside the id must not create extra path segments.
        assertEquals(listOf("message", "m 1/x", "status"), capturedUrl!!.pathSegments)
    }
}
