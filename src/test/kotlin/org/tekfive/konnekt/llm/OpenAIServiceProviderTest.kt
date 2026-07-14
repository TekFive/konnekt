package org.tekfive.konnekt.llm

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.jfk.schema.integerSchema
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.batch.MLBatchItem
import org.tekfive.konnekt.llm.batch.MLBatchStatus
import org.tekfive.konnekt.llm.content.ImageSource
import org.tekfive.konnekt.llm.providers.openai.OpenAIServiceProvider
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAIServiceProviderTest {

    private val endpoint = LlmEndpoint(LlmServiceProviderType.OPENAI, LlmModel.GPT_4O, "sk-test")
    private val miniEndpoint = LlmEndpoint(LlmServiceProviderType.OPENAI, LlmModel.GPT_4O_MINI, "sk-test")

    private fun mockResponse(body: String = ""): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://api.openai.com/v1/chat/completions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody())
            .build()
    }

    // --- Chat request building ---

    @Test
    fun `basic request JSON`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("gpt-4o", json.string("model"))
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
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.7, json["temperature"].double)
    }

    @Test
    fun `system message stays in messages array`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.systemMessage("Be helpful"), LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

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
            endpoint = miniEndpoint,
            maxTokens = 1000,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, miniEndpoint)

        assertEquals(1000, json["max_tokens"].int)
    }

    @Test
    fun `structured output uses json_schema response format`() {
        val schema = objectSchema {
            title = "PersonInfo"
            properties {
                "name" to stringSchema()
                "age" to integerSchema()
            }
            required("name")
        }
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("John is 30")),
            endpoint = endpoint,
            responseSchema = schema,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        val responseFormat = json.reqObj("response_format")
        assertEquals("json_schema", responseFormat.string("type"))
        val jsonSchema = responseFormat.reqObj("json_schema")
        assertEquals("PersonInfo", jsonSchema.string("name"))
        assertNotNull(jsonSchema.obj("schema"))
    }

    @Test
    fun `no response format when no schema`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertTrue(!json.containsKey("response_format"))
    }

    @Test
    fun `top_p set when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            topP = 0.9,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.9, json["top_p"].double)
    }

    @Test
    fun `stop sequences set when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            stopSequences = listOf("END", "STOP"),
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        val stop = json.reqArray("stop")
        assertEquals(2, stop.size)
        assertEquals("END", stop[0].string)
        assertEquals("STOP", stop[1].string)
    }

    @Test
    fun `empty stop sequences are omitted`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            stopSequences = emptyList(),
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertTrue("stop" !in json)
    }

    @Test
    fun `blank stop sequences are omitted`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            stopSequences = listOf("", "   "),
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertTrue("stop" !in json)
    }

    @Test
    fun `presence penalty set when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            presencePenalty = 0.5,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.5, json["presence_penalty"].double)
    }

    @Test
    fun `frequency penalty set when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            frequencyPenalty = 0.3,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.3, json["frequency_penalty"].double)
    }

    // --- Tools ---

    @Test
    fun `tools serialized in request`() {
        val tool = Tool(
            name = "get_weather",
            description = "Get weather for a city",
            parameters = objectSchema {
                properties { "city" to stringSchema { description = "The city name" } }
                required("city")
            },
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather in NYC?")),
            endpoint = endpoint,
            tools = listOf(tool),
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        val tools = json.reqArray("tools")
        assertEquals(1, tools.size)
        val toolJson = tools[0].reqObj
        assertEquals("function", toolJson.string("type"))
        val function = toolJson.reqObj("function")
        assertEquals("get_weather", function.string("name"))
        assertEquals("Get weather for a city", function.string("description"))
        assertNotNull(function.obj("parameters"))
    }

    @Test
    fun `empty tools list not serialized`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            tools = emptyList(),
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertTrue(!json.containsKey("tools"))
    }

    @Test
    fun `tools not serialized when responseSchema is set`() {
        val tool = Tool(
            name = "get_weather",
            description = "Get weather",
            parameters = objectSchema { properties { "city" to stringSchema() } },
        )
        val schema = objectSchema { title = "Result"; properties { "answer" to stringSchema() } }
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            tools = listOf(tool),
            responseSchema = schema,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertTrue(!json.containsKey("tools"))
        assertNotNull(json.obj("response_format"))
    }

    @Test
    fun `tool choice auto`() {
        val tool = Tool("t", "d", objectSchema { properties { "x" to stringSchema() } })
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.Auto,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("auto", json.string("tool_choice"))
    }

    @Test
    fun `tool choice none`() {
        val tool = Tool("t", "d", objectSchema { properties { "x" to stringSchema() } })
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.None,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("none", json.string("tool_choice"))
    }

    @Test
    fun `tool choice required`() {
        val tool = Tool("t", "d", objectSchema { properties { "x" to stringSchema() } })
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.Required,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("required", json.string("tool_choice"))
    }

    @Test
    fun `tool choice specific`() {
        val tool = Tool("get_weather", "d", objectSchema { properties { "x" to stringSchema() } })
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.Specific("get_weather"),
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        val toolChoice = json.reqObj("tool_choice")
        assertEquals("function", toolChoice.string("type"))
        assertEquals("get_weather", toolChoice.reqObj("function").string("name"))
    }

    // --- Multimodal content ---

    @Test
    fun `user message with image URL`() {
        val message =  LlmMessage.userMessage("What is this?", ImageSource.Url("https://example.com/img.png"))
        val request = LlmRequest(
            messages = listOf(message),
            endpoint = endpoint,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val msg = messages[0].reqObj
        val content = msg.reqArray("content")
        assertEquals(2, content.size)

        val textPart = content[0].reqObj
        assertEquals("text", textPart.string("type"))
        assertEquals("What is this?", textPart.string("text"))

        val imagePart = content[1].reqObj
        assertEquals("image_url", imagePart.string("type"))
        assertEquals("https://example.com/img.png", imagePart.reqObj("image_url").string("url"))
    }

    @Test
    fun `user message with base64 image`() {
        val message =  LlmMessage.userMessage("Describe", ImageSource.Base64("image/png", "abc123"))
        val request = LlmRequest(
            messages = listOf(message),
            endpoint = endpoint,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val msg = messages[0].reqObj
        val content = msg.reqArray("content")
        val imagePart = content[1].reqObj
        assertEquals("data:image/png;base64,abc123", imagePart.reqObj("image_url").string("url"))
    }

    // --- Tool use in messages ---

    @Test
    fun `assistant message with tool calls`() {
        val toolUse = LlmContentPart.ToolUse("call_123", "get_weather", JsonObject(mapOf("city" to "NYC")))
        val message = LlmMessage(PromptRole.ASSISTANT, listOf(LlmContentPart.Text("Let me check"), toolUse))
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather?"), message),
            endpoint = endpoint,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val assistantMsg = messages[1].reqObj
        assertEquals("assistant", assistantMsg.string("role"))
        assertEquals("Let me check", assistantMsg.string("content"))

        val toolCalls = assistantMsg.reqArray("tool_calls")
        assertEquals(1, toolCalls.size)
        val tc = toolCalls[0].reqObj
        assertEquals("call_123", tc.string("id"))
        assertEquals("function", tc.string("type"))
        assertEquals("get_weather", tc.reqObj("function").string("name"))
    }

    @Test
    fun `tool result message`() {
        val message = LlmMessage.toolResultMessage("call_123", "72 degrees")
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather?"), message),
            endpoint = endpoint,
        )
        val json = OpenAIServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val toolMsg = messages[1].reqObj
        assertEquals("tool", toolMsg.string("role"))
        assertEquals("call_123", toolMsg.string("tool_call_id"))
        assertEquals("72 degrees", toolMsg.string("content"))
    }

    // --- Chat response parsing ---

    @Test
    fun `parse response with text content`() {
        val responseJson = """
        {
            "choices": [
                {
                    "message": {"role": "assistant", "content": "Hello there!"},
                    "finish_reason": "stop"
                }
            ],
            "model": "gpt-4o-2024-08-06",
            "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
        }
        """.trimIndent()

        val response = OpenAIServiceProvider.parseCompletionResponse(responseJson, mockResponse(), endpoint)

        assertEquals("Hello there!", response.content)
        assertEquals("gpt-4o-2024-08-06", response.model)
        assertEquals(10, response.inputTokens)
        assertEquals(5, response.outputTokens)
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `parse response with reasoning content`() {
        val responseJson = """
        {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "reasoning_content": "I considered the request.",
                        "content": "Hello there!"
                    },
                    "finish_reason": "stop"
                }
            ],
            "model": "deepseek-reasoner",
            "usage": {"prompt_tokens": 10, "completion_tokens": 5}
        }
        """.trimIndent()

        val response = OpenAIServiceProvider.parseCompletionResponse(responseJson, mockResponse(), endpoint)

        assertEquals("Hello there!", response.content)
        assertEquals("I considered the request.", response.reasoning)
    }

    @Test
    fun `parse response without usage`() {
        val responseJson = """
        {
            "choices": [
                {
                    "message": {"role": "assistant", "content": "Hi"},
                    "finish_reason": "stop"
                }
            ],
            "model": "gpt-4o"
        }
        """.trimIndent()

        val response = OpenAIServiceProvider.parseCompletionResponse(responseJson, mockResponse(), endpoint)

        assertEquals("Hi", response.content)
        assertNull(response.inputTokens)
        assertNull(response.outputTokens)
    }

    @Test
    fun `parse response with tool calls`() {
        val responseJson = """
        {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                            {
                                "id": "call_abc",
                                "type": "function",
                                "function": {
                                    "name": "get_weather",
                                    "arguments": "{\"city\":\"NYC\"}"
                                }
                            }
                        ]
                    },
                    "finish_reason": "tool_calls"
                }
            ],
            "model": "gpt-4o",
            "usage": {"prompt_tokens": 10, "completion_tokens": 15, "total_tokens": 25}
        }
        """.trimIndent()

        val response = OpenAIServiceProvider.parseCompletionResponse(responseJson, mockResponse(), endpoint)

        assertTrue(response.hasToolUse)
        assertEquals(1, response.toolUses.size)
        val toolUse = response.toolUses[0]
        assertEquals("call_abc", toolUse.id)
        assertEquals("get_weather", toolUse.name)
        assertEquals("NYC", toolUse.input.string("city"))
        assertEquals(FinishReason.TOOL_USE, response.finishReason)
    }

    // --- Finish reason mapping ---

    @Test
    fun `finish reason stop`() {
        assertEquals(FinishReason.STOP, OpenAICompatibleProvider.mapFinishReason("stop"))
    }

    @Test
    fun `finish reason length maps to MAX_TOKENS`() {
        assertEquals(FinishReason.MAX_TOKENS, OpenAICompatibleProvider.mapFinishReason("length"))
    }

    @Test
    fun `finish reason tool_calls maps to TOOL_USE`() {
        assertEquals(FinishReason.TOOL_USE, OpenAICompatibleProvider.mapFinishReason("tool_calls"))
    }

    @Test
    fun `finish reason content_filter`() {
        assertEquals(FinishReason.CONTENT_FILTER, OpenAICompatibleProvider.mapFinishReason("content_filter"))
    }

    @Test
    fun `finish reason unknown defaults to STOP`() {
        assertEquals(FinishReason.STOP, OpenAICompatibleProvider.mapFinishReason("unknown"))
    }

    // Provider type test removed: circular initialization between MLProvider enum
    // and service provider objects makes direct .provider access unreliable in tests.
    // The mapping is verified via MLProvider.forModel() tests in MLModelTest.

    // --- Embeddings ---

    @Test
    fun `parse embedding response`() {
        val responseJson = """
        {
            "data": [
                {"embedding": [0.1, 0.2, 0.3], "index": 0},
                {"embedding": [0.4, 0.5, 0.6], "index": 1}
            ],
            "model": "text-embedding-3-small",
            "usage": {"prompt_tokens": 5}
        }
        """.trimIndent()

        val response = OpenAIServiceProvider.parseEmbeddingResponse(responseJson)

        assertEquals(2, response.embeddings.size)
        assertEquals("text-embedding-3-small", response.model)
        assertEquals(5, response.inputTokens)
        assertEquals(0.1f, response.embeddings[0][0], 0.001f)
        assertEquals(0.4f, response.embeddings[1][0], 0.001f)
    }

    // --- Batch input building ---

    @Test
    fun `batch input JSONL format`() {
        val items = listOf(
            MLBatchItem("req-1", LlmRequest(listOf(LlmMessage.userMessage("Hello")), endpoint)),
            MLBatchItem("req-2", LlmRequest(listOf(LlmMessage.systemMessage("Be brief"), LlmMessage.userMessage("Hi")), endpoint)),
        )

        val jsonl = OpenAIServiceProvider.buildBatchInputJsonl(items, endpoint)
        val lines = jsonl.lines()
        assertEquals(2, lines.size)

        val first = lines[0].asRequiredJsonObject()
        assertEquals("req-1", first.string("custom_id"))
        assertEquals("POST", first.string("method"))
        assertEquals("/v1/chat/completions", first.string("url"))
        val firstBody = first.reqObj("body")
        assertEquals("gpt-4o", firstBody.string("model"))

        val second = lines[1].asRequiredJsonObject()
        assertEquals("req-2", second.string("custom_id"))
        val secondBody = second.reqObj("body")
        assertEquals("gpt-4o", secondBody.string("model"))
    }

    // --- Batch status parsing ---

    @Test
    fun `parse batch status validating maps to IN_PROGRESS`() {
        assertEquals(MLBatchStatus.IN_PROGRESS, OpenAIServiceProvider.parseBatchStatus("validating"))
    }

    @Test
    fun `parse batch status in_progress`() {
        assertEquals(MLBatchStatus.IN_PROGRESS, OpenAIServiceProvider.parseBatchStatus("in_progress"))
    }

    @Test
    fun `parse batch status finalizing maps to IN_PROGRESS`() {
        assertEquals(MLBatchStatus.IN_PROGRESS, OpenAIServiceProvider.parseBatchStatus("finalizing"))
    }

    @Test
    fun `parse batch status completed`() {
        assertEquals(MLBatchStatus.COMPLETED, OpenAIServiceProvider.parseBatchStatus("completed"))
    }

    @Test
    fun `parse batch status failed`() {
        assertEquals(MLBatchStatus.FAILED, OpenAIServiceProvider.parseBatchStatus("failed"))
    }

    @Test
    fun `parse batch status expired`() {
        assertEquals(MLBatchStatus.EXPIRED, OpenAIServiceProvider.parseBatchStatus("expired"))
    }

    @Test
    fun `parse batch status cancelled`() {
        assertEquals(MLBatchStatus.CANCELLED, OpenAIServiceProvider.parseBatchStatus("cancelled"))
    }

    // --- Batch results parsing ---

    @Test
    fun `parse batch results JSONL with success`() {
        val jsonl = """
            {"custom_id":"req-1","response":{"status_code":200,"body":{"choices":[{"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],"model":"gpt-4o","usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}},"error":null}
            {"custom_id":"req-2","response":{"status_code":200,"body":{"choices":[{"message":{"role":"assistant","content":"Hi!"},"finish_reason":"stop"}],"model":"gpt-4o-mini","usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}},"error":null}
        """.trimIndent()

        val results = OpenAIServiceProvider.parseBatchResultsJsonl(jsonl)

        assertEquals(2, results.size)

        val first = results[0]
        assertEquals("req-1", first.id)
        assertTrue(first.isSuccess)
        assertEquals("Hello!", first.response!!.content)
        assertEquals("gpt-4o", first.response!!.model)
        assertEquals(10, first.response!!.inputTokens)

        val second = results[1]
        assertEquals("req-2", second.id)
        assertTrue(second.isSuccess)
        assertEquals("Hi!", second.response!!.content)
    }

    @Test
    fun `parse batch results JSONL with error`() {
        val jsonl = """{"custom_id":"req-1","response":null,"error":{"message":"Rate limit exceeded"}}"""

        val results = OpenAIServiceProvider.parseBatchResultsJsonl(jsonl)

        assertEquals(1, results.size)
        val result = results[0]
        assertEquals("req-1", result.id)
        assertNull(result.response)
        assertEquals("Rate limit exceeded", result.error)
    }

    @Test
    fun `parse batch results JSONL with non-200 status`() {
        val jsonl = """{"custom_id":"req-1","response":{"status_code":400,"body":{"error":{"message":"Invalid model"}}},"error":null}"""

        val results = OpenAIServiceProvider.parseBatchResultsJsonl(jsonl)

        assertEquals(1, results.size)
        val result = results[0]
        assertEquals("req-1", result.id)
        assertNull(result.response)
        assertNotNull(result.error)
    }
}
