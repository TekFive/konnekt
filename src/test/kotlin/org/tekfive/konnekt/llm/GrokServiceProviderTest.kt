package org.tekfive.konnekt.llm

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.content.ImageSource
import org.tekfive.konnekt.llm.providers.grok.GrokServiceProvider
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrokServiceProviderTest {

    private val endpoint = LlmEndpoint(LlmServiceProviderType.GROK, LlmModel.GROK_3, "sk-test")

    private fun mockResponse(body: String = ""): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://api.x.ai/v1/chat/completions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody())
            .build()
    }

    // --- Provider setup ---

    // Provider type test removed: circular initialization between MLProvider enum
    // and service provider objects makes direct .provider access unreliable in tests.
    // The mapping is verified via MLProvider.forModel() tests in MLModelTest.

    // --- Chat request building ---

    @Test
    fun `basic request JSON`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("grok-3", json.string("model"))
        assertTrue(!json.containsKey("temperature"))
        assertTrue(!json.containsKey("max_tokens"))

        val messages = json.reqArray("messages")
        assertEquals(1, messages.size)
        val msg = messages[0].reqObj
        assertEquals("user", msg.string("role"))
        assertEquals("Hello", msg.string("content"))
    }

    @Test
    fun `request with temperature`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
            temperature = 0.7,
        )
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.7, json["temperature"].double)
    }

    @Test
    fun `system message stays in messages array`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.systemMessage("Be helpful"), LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        assertEquals(2, messages.size)
        val sysMsg = messages[0].reqObj
        assertEquals("system", sysMsg.string("role"))
        assertEquals("Be helpful", sysMsg.string("content"))
    }

    @Test
    fun `max tokens set when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            maxTokens = 1000,
        )
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(1000, json["max_tokens"].int)
    }

    @Test
    fun `top_p set when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            topP = 0.9,
        )
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.9, json["top_p"].double)
    }

    @Test
    fun `stop sequences set when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            stopSequences = listOf("\n\n", "END"),
        )
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        val stop = json.reqArray("stop")
        assertEquals(1, stop.size)
    }

    @Test
    fun `presence and frequency penalty set when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            presencePenalty = 0.5,
            frequencyPenalty = 0.3,
        )
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.5, json["presence_penalty"].double)
        assertEquals(0.3, json["frequency_penalty"].double)
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
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        val tools = json.reqArray("tools")
        assertEquals(1, tools.size)
        val toolJson = tools[0].reqObj
        assertEquals("function", toolJson.string("type"))
        val function = toolJson.reqObj("function")
        assertEquals("get_weather", function.string("name"))
        assertEquals("Get weather", function.string("description"))
        assertNotNull(function.obj("parameters"))

        assertEquals("auto", json.string("tool_choice"))
    }

    @Test
    fun `tool choice specific`() {
        val tool = Tool("search", "Search", objectSchema { properties { "q" to stringSchema() } })
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Find it")),
            endpoint = endpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.Specific("search"),
        )
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        val toolChoice = json.reqObj("tool_choice")
        assertEquals("function", toolChoice.string("type"))
        assertEquals("search", toolChoice.reqObj("function").string("name"))
    }

    @Test
    fun `multimodal content with image URL`() {
        val msg =  LlmMessage.userMessage("Describe this", ImageSource.Url("https://example.com/img.png"))
        val request = LlmRequest(messages = listOf(msg), endpoint = endpoint)
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val content = messages[0].reqObj.reqArray("content")
        assertEquals(2, content.size)

        val textPart = content[0].reqObj
        assertEquals("text", textPart.string("type"))
        assertEquals("Describe this", textPart.string("text"))

        val imagePart = content[1].reqObj
        assertEquals("image_url", imagePart.string("type"))
        assertEquals("https://example.com/img.png", imagePart.reqObj("image_url").string("url"))
    }

    @Test
    fun `multimodal content with base64 image`() {
        val msg =  LlmMessage.userMessage("Describe this", ImageSource.Base64("image/png", "iVBOR"))
        val request = LlmRequest(messages = listOf(msg), endpoint = endpoint)
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val content = messages[0].reqObj.reqArray("content")
        val imagePart = content[1].reqObj
        assertEquals("data:image/png;base64,iVBOR", imagePart.reqObj("image_url").string("url"))
    }

    @Test
    fun `tool result message serialized correctly`() {
        val msg = LlmMessage.toolResultMessage("call-1", "72 degrees")
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("weather"), msg),
            endpoint = endpoint,
        )
        val json = GrokServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        assertEquals(2, messages.size)

        val toolMsg = messages[1].reqObj
        assertEquals("tool", toolMsg.string("role"))
        assertEquals("call-1", toolMsg.string("tool_call_id"))
        assertEquals("72 degrees", toolMsg.string("content"))
    }

    // --- Response parsing ---

    @Test
    fun `parse basic response`() {
        val body = """
        {
            "choices": [{"message": {"content": "Hello!"}, "finish_reason": "stop"}],
            "model": "grok-3",
            "usage": {"prompt_tokens": 10, "completion_tokens": 5}
        }
        """.trimIndent()

        val response = GrokServiceProvider.parseCompletionResponse(body, mockResponse(), endpoint)
        assertEquals("Hello!", response.content)
        assertEquals("grok-3", response.model)
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
                            "name": "get_weather",
                            "arguments": "{\"city\": \"NYC\"}"
                        }
                    }]
                },
                "finish_reason": "tool_calls"
            }],
            "model": "grok-3",
            "usage": {"prompt_tokens": 15, "completion_tokens": 8}
        }
        """.trimIndent()

        val response = GrokServiceProvider.parseCompletionResponse(body, mockResponse(), endpoint)
        assertTrue(response.hasToolUse)
        assertEquals(1, response.toolUses.size)
        assertEquals("call-1", response.toolUses[0].id)
        assertEquals("get_weather", response.toolUses[0].name)
        assertEquals("NYC", response.toolUses[0].input.reqString("city"))
        assertEquals(FinishReason.TOOL_USE, response.finishReason)
    }

    @Test
    fun `parse response without usage`() {
        val body = """
        {
            "choices": [{"message": {"content": "Hi"}, "finish_reason": "stop"}],
            "model": "grok-3"
        }
        """.trimIndent()

        val response = GrokServiceProvider.parseCompletionResponse(body, mockResponse(), endpoint)
        assertEquals("Hi", response.content)
        assertNull(response.inputTokens)
        assertNull(response.outputTokens)
    }

    // --- Finish reason mapping ---

    @Test
    fun `finish reason mapping`() {
        assertEquals(FinishReason.STOP, OpenAICompatibleProvider.mapFinishReason("stop"))
        assertEquals(FinishReason.MAX_TOKENS, OpenAICompatibleProvider.mapFinishReason("length"))
        assertEquals(FinishReason.TOOL_USE, OpenAICompatibleProvider.mapFinishReason("tool_calls"))
        assertEquals(FinishReason.CONTENT_FILTER, OpenAICompatibleProvider.mapFinishReason("content_filter"))
        assertEquals(FinishReason.STOP, OpenAICompatibleProvider.mapFinishReason("unknown"))
    }
}
