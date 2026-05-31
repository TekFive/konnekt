package org.tekfive.konnekt.message.team.providers.tigerconnect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TigerConnectClientTest {

    @Test
    fun `basic auth header is built from key and secret`() {
        val auth = TigerConnectAuth("key", "secret", "https://example.test")

        assertTrue(auth.authorizationHeader.startsWith("Basic "))
        assertEquals("https://example.test", auth.normalizedBaseUrl)
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
            org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectSendRequest(
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
}
