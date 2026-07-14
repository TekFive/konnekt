package org.tekfive.konnekt.llm

import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.content.ImageSource
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider
import org.tekfive.konnekt.llm.providers.vllm.VllmServiceProvider
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VllmServiceProviderTest {

    private val endpoint = LlmEndpoint(LlmServiceProviderType.VLLM, "meta-llama/Llama-3-8b", "sk-test", "http://localhost:8000")
    private val endpointNoKey = LlmEndpoint(LlmServiceProviderType.VLLM, "meta-llama/Llama-3-8b", baseUrl = "http://localhost:8000")
    private val endpointNoModel = LlmEndpoint(LlmServiceProviderType.VLLM, apiKey = "sk-test", baseUrl = "http://localhost:8000")

    // --- Endpoint validation ---

    @Test
    fun `VLLM has no default base URL`() {
        assertNull(LlmServiceProviderType.VLLM.defaultBaseUrl)
    }

    @Test
    fun `endpoint without baseUrl has null resolvedBaseUrl`() {
        val ep = LlmEndpoint(LlmServiceProviderType.VLLM, "model")
        assertNull(ep.resolvedBaseUrl)
    }

    @Test
    fun `chat throws IllegalArgumentException without baseUrl`() {
        val ep = LlmEndpoint(LlmServiceProviderType.VLLM, "model", "sk-test")
        assertFailsWith<LlmException> {
            VllmServiceProvider.chat(
                LlmRequest(messages = listOf(LlmMessage.userMessage("hi")), endpoint = ep),
                ep,
            )
        }
    }



    // --- Chat request building ---

    @Test
    fun `basic request JSON with model`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("meta-llama/Llama-3-8b", json.string("model"))
        val messages = json.reqArray("messages")
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].reqObj.string("role"))
        assertEquals("Hello", messages[0].reqObj.string("content"))
    }

    @Test
    fun `none reasoning effort maps to chat template kwargs`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
            reasoningEffort = LlmReasoningEffort.NONE,
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)

        assertTrue(!json.containsKey("reasoning_effort"))
        assertEquals(false, json.reqObj("chat_template_kwargs")["enable_thinking"].boolean)
    }

    @Test
    fun `none reasoning effort merges with caller chat template kwargs`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
            reasoningEffort = LlmReasoningEffort.NONE,
            extraBodyParameters = JsonObject(mapOf("chat_template_kwargs" to JsonObject(mapOf("custom_flag" to true)))),
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)

        val kwargs = json.reqObj("chat_template_kwargs")
        assertEquals(false, kwargs["enable_thinking"].boolean)
        assertEquals(true, kwargs["custom_flag"].boolean)
    }

    @Test
    fun `caller extra body parameters win over none reasoning mapping`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
            reasoningEffort = LlmReasoningEffort.NONE,
            extraBodyParameters = JsonObject(mapOf("chat_template_kwargs" to JsonObject(mapOf("enable_thinking" to true)))),
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(true, json.reqObj("chat_template_kwargs")["enable_thinking"].boolean)
    }

    @Test
    fun `request JSON without model omits model field`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpointNoModel,
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpointNoModel)

        assertTrue(!json.containsKey("model"))
    }

    @Test
    fun `request with temperature`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
            temperature = 0.7,
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)
        assertEquals(0.7, json["temperature"].double)
    }

    @Test
    fun `system message stays in messages array`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.systemMessage("Be helpful"), LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].reqObj.string("role"))
    }

    @Test
    fun `max tokens and top_p set when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            maxTokens = 1000,
            topP = 0.9,
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)
        assertEquals(1000, json["max_tokens"].int)
        assertEquals(0.9, json["top_p"].double)
    }

    @Test
    fun `top_k supported for VLLM`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            topK = 50,
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)
        assertEquals(50, json["top_k"].int)
    }

    @Test
    fun `extra body parameters are included in VLLM request JSON`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            extraBodyParameters = JsonObject(
                mapOf(
                    "seed" to 123,
                    "stop_token_ids" to JsonArray(listOf(32000, 32001)),
                )
            ),
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(123, json["seed"].int)
        assertEquals(32000, json.reqArray("stop_token_ids")[0].int)
        assertEquals(32001, json.reqArray("stop_token_ids")[1].int)
    }

    @Test
    fun `stop sequences set when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            stopSequences = listOf("\n\n", "END"),
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)
        val stop = json.reqArray("stop")
        assertEquals(1, stop.size)
    }

    @Test
    fun `tools serialized in request`() {
        val tool = Tool("get_weather", "Get weather", objectSchema {
            properties { "city" to stringSchema() }
        })
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather?")),
            endpoint = endpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.Auto,
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)

        val tools = json.reqArray("tools")
        assertEquals(1, tools.size)
        assertEquals("auto", json.string("tool_choice"))
    }

    @Test
    fun `multimodal content with image URL`() {
        val msg =  LlmMessage.userMessage("Describe this", ImageSource.Url("https://example.com/img.png"))
        val request = LlmRequest(messages = listOf(msg), endpoint = endpoint)
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val content = messages[0].reqObj.reqArray("content")
        assertEquals(2, content.size)
        assertEquals("image_url", content[1].reqObj.string("type"))
    }

    @Test
    fun `tool result message serialized correctly`() {
        val msg = LlmMessage.toolResultMessage("call-1", "72 degrees")
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("weather"), msg),
            endpoint = endpoint,
        )
        val json = VllmServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val toolMsg = messages[1].reqObj
        assertEquals("tool", toolMsg.string("role"))
        assertEquals("call-1", toolMsg.string("tool_call_id"))
    }

    // --- Response parsing ---

    @Test
    fun `parse basic response`() {
        val body = """
        {
            "choices": [{"message": {"content": "Hello!"}, "finish_reason": "stop"}],
            "model": "meta-llama/Llama-3-8b",
            "usage": {"prompt_tokens": 10, "completion_tokens": 5}
        }
        """.trimIndent()

        val response = VllmServiceProvider.parseCompletionJsonNoHeaders(body.asRequiredJsonObject(), endpoint)
        assertEquals("Hello!", response.content)
        assertEquals("meta-llama/Llama-3-8b", response.model)
        assertEquals(10, response.inputTokens)
        assertEquals(5, response.outputTokens)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(endpoint, response.endpoint)
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
                            "name": "get_weather",
                            "arguments": "{\"city\": \"NYC\"}"
                        }
                    }]
                },
                "finish_reason": "tool_calls"
            }],
            "model": "meta-llama/Llama-3-8b"
        }
        """.trimIndent()

        val response = VllmServiceProvider.parseCompletionJsonNoHeaders(body.asRequiredJsonObject(), endpoint)
        assertTrue(response.hasToolUse)
        assertEquals("get_weather", response.toolUses[0].name)
        assertEquals(FinishReason.TOOL_USE, response.finishReason)
    }

    @Test
    fun `parse response without model falls back to endpoint model`() {
        val body = """
        {
            "choices": [{"message": {"content": "Hi"}, "finish_reason": "stop"}]
        }
        """.trimIndent()

        val response = VllmServiceProvider.parseCompletionJsonNoHeaders(body.asRequiredJsonObject(), endpoint)
        assertEquals("meta-llama/Llama-3-8b", response.model)
    }

    @Test
    fun `response has no rate limits`() {
        val body = """
        {
            "choices": [{"message": {"content": "Hi"}, "finish_reason": "stop"}],
            "model": "test"
        }
        """.trimIndent()

        val response = VllmServiceProvider.parseCompletionJsonNoHeaders(body.asRequiredJsonObject(), endpoint)
        assertNull(response.rateLimits)
    }

    // --- Finish reason mapping ---

    @Test
    fun `finish reason mapping`() {
        assertEquals(FinishReason.STOP, OpenAICompatibleProvider.mapFinishReason("stop"))
        assertEquals(FinishReason.MAX_TOKENS, OpenAICompatibleProvider.mapFinishReason("length"))
        assertEquals(FinishReason.TOOL_USE, OpenAICompatibleProvider.mapFinishReason("tool_calls"))
        assertEquals(FinishReason.STOP, OpenAICompatibleProvider.mapFinishReason("unknown"))
    }
}
