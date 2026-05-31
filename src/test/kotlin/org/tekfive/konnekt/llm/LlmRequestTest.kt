package org.tekfive.konnekt.llm

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmRequestTest {

    private val testEndpoint = LlmEndpoint(LlmServiceProviderType.ANTHROPIC, LlmModel.CLAUDE_SONNET, "sk-test")

    @Test
    fun `creates request with defaults`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("hello")),
            endpoint = testEndpoint,
        )
        assertNull(request.temperature)
        assertNull(request.maxTokens)
        assertNull(request.extraBodyParameters)
        assertTrue(request.fallbackEndpoints.isEmpty())
        assertNull(request.shouldFallback)
    }

    @Test
    fun `rejects empty messages`() {
        assertFailsWith<IllegalArgumentException> {
            LlmRequest(messages = emptyList(), endpoint = testEndpoint)
        }
    }

    @Test
    fun `rejects negative temperature`() {
        assertFailsWith<IllegalArgumentException> {
            LlmRequest(
                messages = listOf(LlmMessage.userMessage("hi")),
                endpoint = testEndpoint,
                temperature = -0.1,
            )
        }
    }

    @Test
    fun `rejects temperature above 2`() {
        assertFailsWith<IllegalArgumentException> {
            LlmRequest(
                messages = listOf(LlmMessage.userMessage("hi")),
                endpoint = testEndpoint,
                temperature = 2.1,
            )
        }
    }

    @Test
    fun `rejects zero maxTokens`() {
        assertFailsWith<IllegalArgumentException> {
            LlmRequest(
                messages = listOf(LlmMessage.userMessage("hi")),
                endpoint = testEndpoint,
                maxTokens = 0,
            )
        }
    }

    @Test
    fun `accepts request with fallback endpoints`() {
        val fallback = LlmEndpoint(LlmServiceProviderType.OPENAI, LlmModel.GPT_4O, "sk-openai")
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("hi")),
            endpoint = testEndpoint,
            fallbackEndpoints = listOf(fallback),
        )
        assertEquals(1, request.fallbackEndpoints.size)
        assertEquals(LlmServiceProviderType.OPENAI, request.fallbackEndpoints[0].providerType)
    }

    @Test
    fun `accepts valid configuration with all parameters`() {
        val tools = listOf(Tool("search", "Search", objectSchema { properties { "q" to stringSchema() } }))
        val request = LlmRequest(
            messages = listOf(LlmMessage.systemMessage("system"), LlmMessage.userMessage("user")),
            endpoint = LlmEndpoint(LlmServiceProviderType.OPENAI, LlmModel.GPT_4O, "sk-test"),
            temperature = 0.5,
            maxTokens = 4096,
            topP = 0.9,
            topK = 40,
            stopSequences = listOf("\n\n"),
            presencePenalty = 0.5,
            frequencyPenalty = 0.3,
            tools = tools,
            toolChoice = ToolChoice.Auto,
            extraBodyParameters = JsonObject(mapOf("seed" to 123)),
        )
        assertEquals(2, request.messages.size)
        assertEquals(0.5, request.temperature)
        assertEquals(4096, request.maxTokens)
        assertEquals(123, request.extraBodyParameters?.get("seed")?.int)
    }
}
