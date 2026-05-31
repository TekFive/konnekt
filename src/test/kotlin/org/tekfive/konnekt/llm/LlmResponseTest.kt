package org.tekfive.konnekt.llm

import org.tekfive.jfk.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmResponseTest {

    @Test
    fun `content returns concatenated text parts`() {
        val response = LlmResponse(
            contentParts = listOf(LlmContentPart.Text("Hello "), LlmContentPart.Text("world")),
            model = "model",
        )
        assertEquals("Hello world", response.content)
    }

    @Test
    fun `content skips non-text parts`() {
        val response = LlmResponse(
            contentParts = listOf(
                LlmContentPart.Text("Hello"),
                LlmContentPart.ToolUse("id", "name", JsonObject()),
            ),
            model = "model",
        )
        assertEquals("Hello", response.content)
    }

    @Test
    fun `totalTokens sums input and output`() {
        val response = LlmResponse(
            contentParts = listOf(LlmContentPart.Text("hello")),
            model = "model",
            inputTokens = 10,
            outputTokens = 20,
        )
        assertEquals(30, response.totalTokens)
    }

    @Test
    fun `totalTokens is null when input tokens missing`() {
        val response = LlmResponse(
            contentParts = listOf(LlmContentPart.Text("hello")),
            model = "model",
            outputTokens = 20,
        )
        assertNull(response.totalTokens)
    }

    @Test
    fun `totalTokens is null when output tokens missing`() {
        val response = LlmResponse(
            contentParts = listOf(LlmContentPart.Text("hello")),
            model = "model",
            inputTokens = 10,
        )
        assertNull(response.totalTokens)
    }

    @Test
    fun `contentAsJson parses text content as JSON`() {
        val response = LlmResponse(
            contentParts = listOf(LlmContentPart.Text("""{"name": "test"}""")),
            model = "model",
        )
        val json = response.contentAsJson()
        assertEquals("test", json?.string("name"))
    }

    @Test
    fun `toolUses returns tool use parts`() {
        val toolUse = LlmContentPart.ToolUse("id-1", "get_weather", JsonObject(mapOf("city" to "NYC")))
        val response = LlmResponse(
            contentParts = listOf(LlmContentPart.Text("Let me check"), toolUse),
            model = "model",
        )
        assertEquals(1, response.toolUses.size)
        assertEquals("get_weather", response.toolUses[0].name)
    }

    @Test
    fun `hasToolUse is true when tool use parts exist`() {
        val response = LlmResponse(
            contentParts = listOf(LlmContentPart.ToolUse("id", "tool", JsonObject())),
            model = "model",
        )
        assertTrue(response.hasToolUse)
    }

    @Test
    fun `hasToolUse is false when no tool use parts`() {
        val response = LlmResponse(
            contentParts = listOf(LlmContentPart.Text("hello")),
            model = "model",
        )
        assertFalse(response.hasToolUse)
    }

    @Test
    fun `finishReason typed enum`() {
        val response = LlmResponse(
            contentParts = listOf(LlmContentPart.Text("hello")),
            model = "model",
            finishReason = FinishReason.STOP,
        )
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `convenience constructor from text`() {
        val response = LlmResponse.fromText("hello", "model", inputTokens = 5, outputTokens = 10)
        assertEquals("hello", response.content)
        assertEquals(1, response.contentParts.size)
        assertIs<LlmContentPart.Text>(response.contentParts[0])
    }

    @Test
    fun `response includes endpoint`() {
        val endpoint = LlmEndpoint(LlmServiceProviderType.OPENAI, "gpt-4o", "sk-test")
        val response = LlmResponse.fromText("hello", "gpt-4o", endpoint = endpoint)
        assertEquals(endpoint, response.endpoint)
    }

    @Test
    fun `response includes rate limits`() {
        val limits = RateLimits(remainingRequests = 100, remainingTokens = 50000)
        val response = LlmResponse.fromText("hello", "gpt-4o", rateLimits = limits)
        assertEquals(100, response.rateLimits?.remainingRequests)
        assertEquals(50000, response.rateLimits?.remainingTokens)
    }

    @Test
    fun `response includes reasoning text`() {
        val response = LlmResponse.fromText("answer", "model", reasoning = "reasoning trace")
        assertEquals("reasoning trace", response.reasoning)
    }

    @Test
    fun `response endpoint and rateLimits default to null`() {
        val response = LlmResponse.fromText("hello", "model")
        assertNull(response.endpoint)
        assertNull(response.rateLimits)
    }
}
