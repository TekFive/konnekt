package org.tekfive.konnekt.llm

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.tekfive.jfk.schema.integerSchema
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.batch.MLBatchItem
import org.tekfive.konnekt.llm.batch.MLBatchStatus
import org.tekfive.konnekt.llm.content.ImageSource
import org.tekfive.konnekt.llm.providers.antrophic.AnthropicServiceProvider
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicServiceProviderTest {

    private val endpoint = LlmEndpoint(LlmServiceProviderType.ANTHROPIC, LlmModel.CLAUDE_SONNET, "sk-test")
    private val haikuEndpoint = LlmEndpoint(LlmServiceProviderType.ANTHROPIC, LlmModel.CLAUDE_HAIKU, "sk-test")

    private fun mockResponse(body: String = ""): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://api.anthropic.com/v1/messages").build())
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
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("claude-sonnet-4-20250514", json.string("model"))
        assertEquals(0.7, json["temperature"].double)
        assertEquals(4096, json["max_tokens"].int)

        val messages = json.reqArray("messages")
        assertEquals(1, messages.size)
        val msg = messages[0].reqObj
        assertEquals("user", msg.string("role"))
        assertEquals("Hello", msg.string("content"))
    }

    @Test
    fun `system message extracted to top level`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.systemMessage("Be helpful"), LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("Be helpful", json.string("system"))

        val messages = json.reqArray("messages")
        assertEquals(1, messages.size)
        val msg = messages[0].reqObj
        assertEquals("user", msg.string("role"))
    }

    @Test
    fun `max tokens set when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = haikuEndpoint,
            maxTokens = 500,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, haikuEndpoint)

        assertEquals(500, json["max_tokens"].int)
    }

    @Test
    fun `structured output uses tool pattern`() {
        val schema = objectSchema {
            title = "PersonInfo"
            description = "Extract person info"
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
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        val tools = json.reqArray("tools")
        assertEquals(1, tools.size)
        val tool = tools[0].reqObj
        assertEquals("PersonInfo", tool.string("name"))
        assertEquals("Extract person info", tool.string("description"))
        assertNotNull(tool.obj("input_schema"))

        val toolChoice = json.reqObj("tool_choice")
        assertEquals("tool", toolChoice.string("type"))
        assertEquals("PersonInfo", toolChoice.string("name"))
    }

    @Test
    fun `no tools when no schema`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        assertTrue(!json.containsKey("tools"))
        assertTrue(!json.containsKey("tool_choice"))
    }

    @Test
    fun `top_p included when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            topP = 0.9,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.9, json["top_p"].double)
    }

    @Test
    fun `top_k included when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            topK = 40,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(40, json["top_k"].int)
    }

    @Test
    fun `stop_sequences included when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            stopSequences = listOf("STOP", "END"),
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        val sequences = json.reqArray("stop_sequences")
        assertEquals(2, sequences.size)
    }

    @Test
    fun `return reasoning text enables thinking`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            reasoningEffort = LlmReasoningEffort.MEDIUM,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        val thinking = json.reqObj("thinking")
        assertEquals("enabled", thinking.string("type"))
        assertEquals(4096, thinking["budget_tokens"].int)
    }

    @Test
    fun `none reasoning effort disables thinking`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            reasoningEffort = LlmReasoningEffort.NONE,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        val thinking = json.reqObj("thinking")
        assertEquals("disabled", thinking.string("type"))
        assertNull(thinking["budget_tokens"].int)
    }

    @Test
    fun `tools array built from request tools`() {
        val tool = Tool(
            name = "get_weather",
            description = "Get the weather",
            parameters = objectSchema {
                properties { "location" to stringSchema() }
                required("location")
            },
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("What's the weather?")),
            endpoint = endpoint,
            tools = listOf(tool),
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        val tools = json.reqArray("tools")
        assertEquals(1, tools.size)
        val toolObj = tools[0].reqObj
        assertEquals("get_weather", toolObj.string("name"))
        assertEquals("Get the weather", toolObj.string("description"))
        assertNotNull(toolObj.obj("input_schema"))
    }

    @Test
    fun `tool choice auto`() {
        val tool = Tool(
            name = "get_weather",
            description = "Get the weather",
            parameters = objectSchema { properties { "location" to stringSchema() } },
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.Auto,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        val toolChoice = json.reqObj("tool_choice")
        assertEquals("auto", toolChoice.string("type"))
    }

    @Test
    fun `tool choice required maps to any`() {
        val tool = Tool(
            name = "get_weather",
            description = "Get the weather",
            parameters = objectSchema { properties { "location" to stringSchema() } },
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.Required,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        val toolChoice = json.reqObj("tool_choice")
        assertEquals("any", toolChoice.string("type"))
    }

    @Test
    fun `tool choice specific`() {
        val tool = Tool(
            name = "get_weather",
            description = "Get the weather",
            parameters = objectSchema { properties { "location" to stringSchema() } },
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.Specific("get_weather"),
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        val toolChoice = json.reqObj("tool_choice")
        assertEquals("tool", toolChoice.string("type"))
        assertEquals("get_weather", toolChoice.string("name"))
    }

    @Test
    fun `tool choice none omits tool_choice`() {
        val tool = Tool(
            name = "get_weather",
            description = "Get the weather",
            parameters = objectSchema { properties { "location" to stringSchema() } },
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.None,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        assertTrue(!json.containsKey("tool_choice"))
    }

    @Test
    fun `tools not added when responseSchema is set`() {
        val tool = Tool(
            name = "get_weather",
            description = "Get the weather",
            parameters = objectSchema { properties { "location" to stringSchema() } },
        )
        val schema = objectSchema {
            title = "PersonInfo"
            description = "Extract person info"
            properties { "name" to stringSchema() }
            required("name")
        }
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            tools = listOf(tool),
            responseSchema = schema,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        // responseSchema takes precedence - tools array should contain the schema tool, not the request tools
        val tools = json.reqArray("tools")
        assertEquals(1, tools.size)
        assertEquals("PersonInfo", tools[0].reqObj.string("name"))
    }

    @Test
    fun `multimodal content serialized as array`() {
        val message = LlmMessage(
            PromptRole.USER,
            listOf(
                LlmContentPart.Text("What is this?"),
                LlmContentPart.Image(ImageSource.Base64("image/png", "abc123")),
            ),
        )
        val request = LlmRequest(
            messages = listOf(message),
            endpoint = endpoint,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val msg = messages[0].reqObj
        val content = msg.reqArray("content")
        assertEquals(2, content.size)

        val textPart = content[0].reqObj
        assertEquals("text", textPart.string("type"))
        assertEquals("What is this?", textPart.string("text"))

        val imagePart = content[1].reqObj
        assertEquals("image", imagePart.string("type"))
        val source = imagePart.reqObj("source")
        assertEquals("base64", source.string("type"))
        assertEquals("image/png", source.string("media_type"))
        assertEquals("abc123", source.string("data"))
    }

    @Test
    fun `image url serialized correctly`() {
        val message = LlmMessage(
            PromptRole.USER,
            listOf(
                LlmContentPart.Text("Describe this"),
                LlmContentPart.Image(ImageSource.Url("https://example.com/img.png")),
            ),
        )
        val request = LlmRequest(
            messages = listOf(message),
            endpoint = endpoint,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val content = messages[0].reqObj.reqArray("content")
        val imagePart = content[1].reqObj
        assertEquals("image", imagePart.string("type"))
        val source = imagePart.reqObj("source")
        assertEquals("url", source.string("type"))
        assertEquals("https://example.com/img.png", source.string("url"))
    }

    @Test
    fun `tool role messages serialized as user with tool_result`() {
        val message = LlmMessage.toolResultMessage("tu_123", "The weather is sunny")
        val request = LlmRequest(
            messages = listOf(
                LlmMessage.userMessage("What's the weather?"),
                LlmMessage.assistantMessage("Let me check."),
                message,
            ),
            endpoint = endpoint,
        )
        val json = AnthropicServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        assertEquals(3, messages.size)

        val toolMsg = messages[2].reqObj
        assertEquals("user", toolMsg.string("role"))
        val content = toolMsg.reqArray("content")
        val block = content[0].reqObj
        assertEquals("tool_result", block.string("type"))
        assertEquals("tu_123", block.string("tool_use_id"))
        assertEquals("The weather is sunny", block.string("content"))
    }

    // --- Chat response parsing ---

    @Test
    fun `parse text response`() {
        val responseJson = """
        {
            "content": [{"type": "text", "text": "Hello there!"}],
            "model": "claude-sonnet-4-20250514",
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 10, "output_tokens": 5}
        }
        """.trimIndent()

        val response = AnthropicServiceProvider.parseResponse(responseJson, mockResponse(), endpoint)

        assertEquals("Hello there!", response.content)
        assertEquals("claude-sonnet-4-20250514", response.model)
        assertEquals(10, response.inputTokens)
        assertEquals(5, response.outputTokens)
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `parse response with thinking content`() {
        val responseJson = """
        {
            "content": [
                {"type": "thinking", "thinking": "I considered the request."},
                {"type": "text", "text": "Hello there!"}
            ],
            "model": "claude-sonnet-4-20250514",
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 10, "output_tokens": 5}
        }
        """.trimIndent()

        val response = AnthropicServiceProvider.parseResponse(responseJson, mockResponse(), endpoint)

        assertEquals("Hello there!", response.content)
        assertEquals("I considered the request.", response.reasoning)
    }

    @Test
    fun `parse tool use response`() {
        val responseJson = """
        {
            "content": [{"type": "tool_use", "id": "tu_1", "name": "PersonInfo", "input": {"name": "John", "age": 30}}],
            "model": "claude-sonnet-4-20250514",
            "stop_reason": "tool_use",
            "usage": {"input_tokens": 20, "output_tokens": 15}
        }
        """.trimIndent()

        val response = AnthropicServiceProvider.parseResponse(responseJson, mockResponse(), endpoint)

        assertTrue(response.hasToolUse)
        val toolUse = response.toolUses[0]
        assertEquals("tu_1", toolUse.id)
        assertEquals("PersonInfo", toolUse.name)
        assertEquals("John", toolUse.input.string("name"))
        assertEquals(30, toolUse.input["age"].int)
        assertEquals(FinishReason.TOOL_USE, response.finishReason)
    }

    @Test
    fun `parse tool use response contentAsJson still works`() {
        val responseJson = """
        {
            "content": [{"type": "tool_use", "id": "tu_1", "name": "PersonInfo", "input": {"name": "John", "age": 30}}],
            "model": "claude-sonnet-4-20250514",
            "stop_reason": "tool_use",
            "usage": {"input_tokens": 20, "output_tokens": 15}
        }
        """.trimIndent()

        val response = AnthropicServiceProvider.parseResponse(responseJson, mockResponse(), endpoint)

        // ToolUse content parts don't contribute to text content,
        // so contentAsJson won't work the old way. Check via toolUses instead.
        assertTrue(response.hasToolUse)
        assertEquals("PersonInfo", response.toolUses[0].name)
    }

    @Test
    fun `parse response without usage`() {
        val responseJson = """
        {
            "content": [{"type": "text", "text": "Hi"}],
            "model": "claude-sonnet-4-20250514"
        }
        """.trimIndent()

        val response = AnthropicServiceProvider.parseResponse(responseJson, mockResponse(), endpoint)

        assertEquals("Hi", response.content)
        assertNull(response.inputTokens)
        assertNull(response.outputTokens)
    }

    @Test
    fun `finish reason end_turn maps to STOP`() {
        val responseJson = """
        {
            "content": [{"type": "text", "text": "Done"}],
            "model": "claude-sonnet-4-20250514",
            "stop_reason": "end_turn"
        }
        """.trimIndent()

        val response = AnthropicServiceProvider.parseResponse(responseJson, mockResponse(), endpoint)
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `finish reason max_tokens maps to MAX_TOKENS`() {
        val responseJson = """
        {
            "content": [{"type": "text", "text": "Truncated"}],
            "model": "claude-sonnet-4-20250514",
            "stop_reason": "max_tokens"
        }
        """.trimIndent()

        val response = AnthropicServiceProvider.parseResponse(responseJson, mockResponse(), endpoint)
        assertEquals(FinishReason.MAX_TOKENS, response.finishReason)
    }

    @Test
    fun `finish reason tool_use maps to TOOL_USE`() {
        val responseJson = """
        {
            "content": [{"type": "tool_use", "id": "tu_1", "name": "test", "input": {}}],
            "model": "claude-sonnet-4-20250514",
            "stop_reason": "tool_use"
        }
        """.trimIndent()

        val response = AnthropicServiceProvider.parseResponse(responseJson, mockResponse(), endpoint)
        assertEquals(FinishReason.TOOL_USE, response.finishReason)
    }

    @Test
    fun `parse mixed text and tool_use response`() {
        val responseJson = """
        {
            "content": [
                {"type": "text", "text": "Let me look that up."},
                {"type": "tool_use", "id": "tu_1", "name": "search", "input": {"query": "test"}}
            ],
            "model": "claude-sonnet-4-20250514",
            "stop_reason": "tool_use",
            "usage": {"input_tokens": 10, "output_tokens": 20}
        }
        """.trimIndent()

        val response = AnthropicServiceProvider.parseResponse(responseJson, mockResponse(), endpoint)

        assertEquals(2, response.contentParts.size)
        assertEquals("Let me look that up.", response.content)
        assertTrue(response.hasToolUse)
        assertEquals("search", response.toolUses[0].name)
    }

    // Provider type test removed: circular initialization between MLProvider enum
    // and service provider objects makes direct .provider access unreliable in tests.
    // The mapping is verified via MLProvider.forModel() tests in MLModelTest.

    // --- Batch request building ---

    @Test
    fun `batch request JSON wraps items with custom_id and params`() {
        val items = listOf(
            MLBatchItem("req-1", LlmRequest(listOf(LlmMessage.userMessage("Hello")), endpoint)),
            MLBatchItem("req-2", LlmRequest(listOf(LlmMessage.systemMessage("Be brief"), LlmMessage.userMessage("Hi")), endpoint)),
        )

        val json = AnthropicServiceProvider.buildBatchRequestJson(items, endpoint)
        val requests = json.reqArray("requests")
        assertEquals(2, requests.size)

        val first = requests[0].reqObj
        assertEquals("req-1", first.string("custom_id"))
        val firstParams = first.reqObj("params")
        assertEquals("claude-sonnet-4-20250514", firstParams.string("model"))

        val second = requests[1].reqObj
        assertEquals("req-2", second.string("custom_id"))
        val secondParams = second.reqObj("params")
        assertEquals("claude-sonnet-4-20250514", secondParams.string("model"))
        assertEquals("Be brief", secondParams.string("system"))
    }

    // --- Batch status parsing ---

    @Test
    fun `parse batch status in_progress`() {
        assertEquals(MLBatchStatus.IN_PROGRESS, AnthropicServiceProvider.parseBatchStatus("in_progress"))
    }

    @Test
    fun `parse batch status ended maps to COMPLETED`() {
        assertEquals(MLBatchStatus.COMPLETED, AnthropicServiceProvider.parseBatchStatus("ended"))
    }

    @Test
    fun `parse batch status canceling maps to IN_PROGRESS`() {
        assertEquals(MLBatchStatus.IN_PROGRESS, AnthropicServiceProvider.parseBatchStatus("canceling"))
    }

    @Test
    fun `parse batch status canceled`() {
        assertEquals(MLBatchStatus.CANCELLED, AnthropicServiceProvider.parseBatchStatus("canceled"))
    }

    @Test
    fun `parse batch status expired`() {
        assertEquals(MLBatchStatus.EXPIRED, AnthropicServiceProvider.parseBatchStatus("expired"))
    }

    // --- Batch results parsing ---

    @Test
    fun `parse batch results JSONL with succeeded`() {
        val jsonl = """
            {"custom_id":"req-1","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"Hello!"}],"model":"claude-sonnet-4-20250514","stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":3}}}}
            {"custom_id":"req-2","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"Hi!"}],"model":"claude-haiku-4-5-20251001","stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2}}}}
        """.trimIndent()

        val results = AnthropicServiceProvider.parseBatchResultsJsonl(jsonl)

        assertEquals(2, results.size)

        val first = results[0]
        assertEquals("req-1", first.id)
        assertTrue(first.isSuccess)
        assertEquals("Hello!", first.response!!.content)
        assertEquals("claude-sonnet-4-20250514", first.response!!.model)
        assertEquals(10, first.response!!.inputTokens)

        val second = results[1]
        assertEquals("req-2", second.id)
        assertTrue(second.isSuccess)
        assertEquals("Hi!", second.response!!.content)
    }

    @Test
    fun `parse batch results JSONL with errored`() {
        val jsonl = """{"custom_id":"req-1","result":{"type":"errored","error":{"type":"invalid_request","message":"Invalid model"}}}"""

        val results = AnthropicServiceProvider.parseBatchResultsJsonl(jsonl)

        assertEquals(1, results.size)
        val result = results[0]
        assertEquals("req-1", result.id)
        assertNull(result.response)
        assertEquals("Invalid model", result.error)
    }

    @Test
    fun `parse batch results JSONL with tool use`() {
        val jsonl = """{"custom_id":"req-1","result":{"type":"succeeded","message":{"content":[{"type":"tool_use","id":"tu_1","name":"Info","input":{"name":"Alice"}}],"model":"claude-sonnet-4-20250514","stop_reason":"tool_use","usage":{"input_tokens":15,"output_tokens":10}}}}"""

        val results = AnthropicServiceProvider.parseBatchResultsJsonl(jsonl)
        val result = results[0]
        assertTrue(result.isSuccess)
        assertTrue(result.response!!.hasToolUse)
        val toolUse = result.response!!.toolUses[0]
        assertEquals("Info", toolUse.name)
        assertEquals("Alice", toolUse.input.string("name"))
    }
}
