package org.tekfive.konnekt.llm

import org.tekfive.konnekt.llm.embedding.EmbeddingProvider
import org.tekfive.konnekt.llm.embedding.EmbeddingRequest
import org.tekfive.konnekt.llm.embedding.EmbeddingResponse
import org.tekfive.konnekt.llm.providers.LlmServiceProvider
import org.tekfive.konnekt.llm.stream.StreamListener
import org.tekfive.konnekt.llm.stream.StreamingProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MLServiceTest {

    private val openaiEndpoint = LlmEndpoint(LlmServiceProviderType.OPENAI, LlmModel.GPT_4O, "sk-test")
    private val anthropicEndpoint = LlmEndpoint(LlmServiceProviderType.ANTHROPIC, LlmModel.CLAUDE_SONNET, "sk-test")
    private val googleEndpoint = LlmEndpoint(LlmServiceProviderType.GOOGLE, LlmModel.GEMINI_2_FLASH, "sk-test")

    @AfterTest
    fun cleanup() {
        LlmService.clearOverrides()
        LlmService.clearCooldowns()
    }

    private fun createTestChatProvider(providerType: LlmServiceProviderType): ChatProvider {
        return object : ChatProvider {
            override val type
                get()= providerType
            override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse {
                return LlmResponse.fromText(
                    text = "response from ${providerType.displayName}",
                    model = endpoint.model ?: "unknown",
                    inputTokens = 10,
                    outputTokens = 5,
                )
            }
        }
    }

    private fun createTestStreamingProvider(providerType: LlmServiceProviderType): LlmServiceProvider {
        return object : ChatProvider, StreamingProvider {
            override val type
                get()= providerType
            override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse {
                return LlmResponse.fromText("response", model = endpoint.model ?: "unknown")
            }
            override fun chatStream(request: LlmRequest, endpoint: LlmEndpoint, listener: StreamListener) {
                listener.onToken("hello ")
                listener.onToken("world")
                listener.onComplete(LlmResponse.fromText("hello world", model = endpoint.model ?: "unknown"))
            }
        }
    }

    private fun createTestEmbeddingProvider(providerType: LlmServiceProviderType): LlmServiceProvider {
        return object : ChatProvider, EmbeddingProvider {
            override val type
                get() = providerType
            override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse {
                return LlmResponse.fromText("response", model = endpoint.model ?: "unknown")
            }
            override fun embed(request: EmbeddingRequest, endpoint: LlmEndpoint): EmbeddingResponse {
                return EmbeddingResponse(
                    embeddings = request.input.map { floatArrayOf(0.1f, 0.2f, 0.3f) },
                    model = endpoint.model ?: "unknown",
                    inputTokens = request.input.size,
                )
            }
        }
    }

    @Test
    fun `chat dispatches to correct provider`() {
        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, createTestChatProvider(LlmServiceProviderType.OPENAI))
        val response = LlmService.chat(
            LlmRequest(messages = listOf(LlmMessage.userMessage("hello")), endpoint = openaiEndpoint)
        )
        assertEquals("response from OpenAI", response.content)
        assertEquals("gpt-4o", response.model)
    }

    @Test
    fun `simple chat convenience method works`() {
        LlmService.overrideProvider(LlmServiceProviderType.GOOGLE, createTestChatProvider(LlmServiceProviderType.GOOGLE))
        val result = LlmService.chat("hello", googleEndpoint)
        assertEquals("response from Google", result)
    }

    @Test
    fun `chat with system prompt and user content`() {
        LlmService.overrideProvider(LlmServiceProviderType.ANTHROPIC, createTestChatProvider(LlmServiceProviderType.ANTHROPIC))
        val response = LlmService.chat("You are helpful.", "Hello!", anthropicEndpoint)
        assertEquals("response from Anthropic", response.content)
    }

    @Test
    fun `throws when provider does not support chat`() {
        val baseOnlyProvider = object : LlmServiceProvider {
            override val type
                get() = LlmServiceProviderType.OPENAI
        }
        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, baseOnlyProvider)
        assertFailsWith<LlmException> {
            LlmService.chat(LlmRequest(listOf(LlmMessage.userMessage("hi")), endpoint = openaiEndpoint))
        }
    }

    @Test
    fun `chatStream dispatches to streaming provider`() {
        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, createTestStreamingProvider(LlmServiceProviderType.OPENAI))
        val tokens = mutableListOf<String>()
        var completed: LlmResponse? = null
        LlmService.chatStream(
            LlmRequest(listOf(LlmMessage.userMessage("hi")), endpoint = openaiEndpoint),
            object : StreamListener {
                override fun onToken(text: String) { tokens.add(text) }
                override fun onComplete(response: LlmResponse) { completed = response }
                override fun onError(exception: LlmException) {}
            },
        )
        assertEquals(listOf("hello ", "world"), tokens)
        assertEquals("hello world", completed!!.content)
    }

    @Test
    fun `chatStream throws when provider does not support streaming`() {
        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, createTestChatProvider(LlmServiceProviderType.OPENAI))
        assertFailsWith<LlmException> {
            LlmService.chatStream(
                LlmRequest(listOf(LlmMessage.userMessage("hi")), endpoint = openaiEndpoint),
                object : StreamListener {
                    override fun onToken(text: String) {}
                    override fun onComplete(response: LlmResponse) {}
                    override fun onError(exception: LlmException) {}
                },
            )
        }
    }

    @Test
    fun `embed dispatches to embedding provider`() {
        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, createTestEmbeddingProvider(LlmServiceProviderType.OPENAI))
        val response = LlmService.embed(EmbeddingRequest(listOf("hello"), LlmModel.GPT_4O), openaiEndpoint)
        assertEquals(1, response.embeddings.size)
        assertEquals(3, response.embeddings[0].size)
    }

    @Test
    fun `embed throws when provider does not support embeddings`() {
        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, createTestChatProvider(LlmServiceProviderType.OPENAI))
        assertFailsWith<LlmException> {
            LlmService.embed(EmbeddingRequest(listOf("hello"), LlmModel.GPT_4O), openaiEndpoint)
        }
    }

    @Test
    fun `fallback to next endpoint on failure`() {
        val failProvider = object : ChatProvider {
            override val type
                get() = LlmServiceProviderType.OPENAI
            override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse {
                throw LlmException("OpenAI failed")
            }
        }
        val successProvider = createTestChatProvider(LlmServiceProviderType.ANTHROPIC)

        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, failProvider)
        LlmService.overrideProvider(LlmServiceProviderType.ANTHROPIC, successProvider)

        val response = LlmService.chat(
            LlmRequest(
                messages = listOf(LlmMessage.userMessage("hello")),
                endpoint = openaiEndpoint,
                fallbackEndpoints = listOf(anthropicEndpoint),
            )
        )
        assertEquals("response from Anthropic", response.content)
    }
}
