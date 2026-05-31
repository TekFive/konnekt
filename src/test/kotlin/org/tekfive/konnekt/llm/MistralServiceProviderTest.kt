package org.tekfive.konnekt.llm

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.providers.mistral.MistralServiceProvider
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MistralServiceProviderTest {

    private val endpoint = LlmEndpoint(LlmServiceProviderType.MISTRAL, LlmModel.MISTRAL_LARGE, "sk-test")

    private fun mockResponse(body: String = ""): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://api.mistral.ai/v1/chat/completions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody())
            .build()
    }

    @Test
    fun `basic request JSON`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = MistralServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("mistral-large-latest", json.string("model"))
        val messages = json.reqArray("messages")
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].reqObj.string("role"))
        assertEquals("Hello", messages[0].reqObj.string("content"))
    }

    @Test
    fun `system message stays in messages array`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.systemMessage("Be helpful"), LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = MistralServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].reqObj.string("role"))
    }

    @Test
    fun `request with temperature and max tokens`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            temperature = 0.5,
            maxTokens = 2000,
        )
        val json = MistralServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.5, json["temperature"].double)
        assertEquals(2000, json["max_tokens"].int)
    }

    @Test
    fun `tools serialized in request`() {
        val tool = Tool("search", "Search", objectSchema {
            properties { "query" to stringSchema() }
        })
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Find it")),
            endpoint = endpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.Auto,
        )
        val json = MistralServiceProvider.buildRequestJson(request, endpoint)

        val tools = json.reqArray("tools")
        assertEquals(1, tools.size)
        assertEquals("auto", json.string("tool_choice"))
    }

    @Test
    fun `parse basic response`() {
        val body = """
        {
            "choices": [{"message": {"content": "Hello!"}, "finish_reason": "stop"}],
            "model": "mistral-large-latest",
            "usage": {"prompt_tokens": 10, "completion_tokens": 5}
        }
        """.trimIndent()

        val response = MistralServiceProvider.parseCompletionResponse(body, mockResponse(body), endpoint)
        assertEquals("Hello!", response.content)
        assertEquals("mistral-large-latest", response.model)
        assertEquals(10, response.inputTokens)
        assertEquals(5, response.outputTokens)
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `parse response with tool calls`() {
        val body = """
        {
            "choices": [{
                "message": {
                    "content": null,
                    "tool_calls": [{
                        "id": "call-1",
                        "type": "function",
                        "function": {
                            "name": "search",
                            "arguments": "{\"query\": \"kotlin\"}"
                        }
                    }]
                },
                "finish_reason": "tool_calls"
            }],
            "model": "mistral-large-latest"
        }
        """.trimIndent()

        val response = MistralServiceProvider.parseCompletionResponse(body, mockResponse(body), endpoint)
        assertTrue(response.hasToolUse)
        assertEquals("search", response.toolUses[0].name)
        assertEquals(FinishReason.TOOL_USE, response.finishReason)
    }

    @Test
    fun `default base URL is mistral API`() {
        assertEquals("https://api.mistral.ai", LlmServiceProviderType.MISTRAL.defaultBaseUrl)
    }
}
