package org.tekfive.konnekt.llm

import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.jfk.schema.integerSchema
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import org.tekfive.konnekt.llm.providers.bedrock.AwsSignatureV4
import org.tekfive.konnekt.llm.providers.bedrock.BedrockServiceProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BedrockServiceProviderTest {

    private val endpoint = LlmEndpoint(
        providerType = LlmServiceProviderType.BEDROCK,
        model = "anthropic.claude-sonnet-4-20250514-v1:0",
        apiKey = "AKIAIOSFODNN7EXAMPLE:wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
    )

    // --- Credential parsing ---

    @Test
    fun `parse credentials from apiKey`() {
        val (accessKeyId, secretAccessKey) = BedrockServiceProvider.parseCredentials(endpoint)
        assertEquals("AKIAIOSFODNN7EXAMPLE", accessKeyId)
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", secretAccessKey)
    }

    @Test
    fun `parse credentials fails with missing apiKey`() {
        val noKeyEndpoint = endpoint.copy(apiKey = null)
        assertFailsWith<LlmException> {
            BedrockServiceProvider.parseCredentials(noKeyEndpoint)
        }
    }

    @Test
    fun `parse credentials fails with invalid format`() {
        val badKeyEndpoint = endpoint.copy(apiKey = "no-colon-here")
        assertFailsWith<LlmException> {
            BedrockServiceProvider.parseCredentials(badKeyEndpoint)
        }
    }

    @Test
    fun `parse credentials fails with empty parts`() {
        val badKeyEndpoint = endpoint.copy(apiKey = ":secret")
        assertFailsWith<LlmException> {
            BedrockServiceProvider.parseCredentials(badKeyEndpoint)
        }
    }

    // --- AWS region extraction ---

    @Test
    fun `extract region from base URL`() {
        val region = AwsSignatureV4.extractRegion("https://bedrock-runtime.us-east-1.amazonaws.com")
        assertEquals("us-east-1", region)
    }

    @Test
    fun `extract region from eu-west-1 URL`() {
        val region = AwsSignatureV4.extractRegion("https://bedrock-runtime.eu-west-1.amazonaws.com")
        assertEquals("eu-west-1", region)
    }

    @Test
    fun `extract region fails for invalid URL`() {
        assertFailsWith<IllegalArgumentException> {
            AwsSignatureV4.extractRegion("https://api.example.com")
        }
    }

    // --- Request building ---

    @Test
    fun `basic request JSON`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        // Should not contain model in body (it's in the URL path)
        assertNull(json.string("model"))

        val inferenceConfig = json.reqObj("inferenceConfig")
        assertEquals(0.7, inferenceConfig["temperature"].double)
        assertEquals(4096, inferenceConfig["maxTokens"].int)

        val messages = json.reqArray("messages")
        assertEquals(1, messages.size)
        val msg = messages[0].reqObj
        assertEquals("user", msg.string("role"))
        val content = msg.reqArray("content")
        assertEquals(1, content.size)
        assertEquals("Hello", content[0].reqObj.string("text"))
    }

    @Test
    fun `system message extracted to top-level system field`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.systemMessage("Be helpful"), LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        val system = json.reqArray("system")
        assertEquals(1, system.size)
        assertEquals("Be helpful", system[0].reqObj.string("text"))

        // System should not appear in messages
        val messages = json.reqArray("messages")
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].reqObj.string("role"))
    }

    @Test
    fun `temperature and maxTokens from request`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            temperature = 0.3,
            maxTokens = 500,
        )
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        val inferenceConfig = json.reqObj("inferenceConfig")
        assertEquals(0.3, inferenceConfig["temperature"].double)
        assertEquals(500, inferenceConfig["maxTokens"].int)
    }

    @Test
    fun `topP included in inferenceConfig when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            topP = 0.9,
        )
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        val inferenceConfig = json.reqObj("inferenceConfig")
        assertEquals(0.9, inferenceConfig["topP"].double)
    }

    @Test
    fun `stopSequences included in inferenceConfig when specified`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            stopSequences = listOf("STOP", "END"),
        )
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        val inferenceConfig = json.reqObj("inferenceConfig")
        val sequences = inferenceConfig.reqArray("stopSequences")
        assertEquals(2, sequences.size)
    }

    @Test
    fun `return reasoning text adds thinking request field`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            reasoningEffort = LlmReasoningEffort.MEDIUM,
        )
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        val thinking = json.reqObj("additionalModelRequestFields").reqObj("thinking")
        assertEquals("enabled", thinking.string("type"))
        assertEquals(4096, thinking["budget_tokens"].int)
    }

    @Test
    fun `tools array built with toolSpec format`() {
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
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        val toolConfig = json.reqObj("toolConfig")
        val tools = toolConfig.reqArray("tools")
        assertEquals(1, tools.size)
        val toolSpec = tools[0].reqObj.reqObj("toolSpec")
        assertEquals("get_weather", toolSpec.string("name"))
        assertEquals("Get the weather", toolSpec.string("description"))
        assertNotNull(toolSpec.obj("inputSchema"))
        assertNotNull(toolSpec.reqObj("inputSchema").obj("json"))
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
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        val toolConfig = json.reqObj("toolConfig")
        val toolChoice = toolConfig.reqObj("toolChoice")
        assertNotNull(toolChoice.obj("auto"))
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
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        val toolConfig = json.reqObj("toolConfig")
        val toolChoice = toolConfig.reqObj("toolChoice")
        assertNotNull(toolChoice.obj("any"))
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
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        val toolConfig = json.reqObj("toolConfig")
        val toolChoice = toolConfig.reqObj("toolChoice")
        val toolObj = toolChoice.reqObj("tool")
        assertEquals("get_weather", toolObj.string("name"))
    }

    @Test
    fun `no toolConfig when no tools`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        assertTrue(!json.containsKey("toolConfig"))
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
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        val toolConfig = json.reqObj("toolConfig")
        val tools = toolConfig.reqArray("tools")
        assertEquals(1, tools.size)
        val toolSpec = tools[0].reqObj.reqObj("toolSpec")
        assertEquals("PersonInfo", toolSpec.string("name"))
        assertEquals("Extract person info", toolSpec.string("description"))

        val toolChoice = toolConfig.reqObj("toolChoice")
        val toolObj = toolChoice.reqObj("tool")
        assertEquals("PersonInfo", toolObj.string("name"))
    }

    @Test
    fun `tool result messages serialized as user with toolResult`() {
        val message = LlmMessage.toolResultMessage("tu_123", "The weather is sunny")
        val request = LlmRequest(
            messages = listOf(
                 LlmMessage.userMessage("What's the weather?"),
                 LlmMessage.assistantMessage("Let me check."),
                 message,
            ),
            endpoint = endpoint,
        )
        val json = BedrockServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        assertEquals(3, messages.size)

        val toolMsg = messages[2].reqObj
        assertEquals("user", toolMsg.string("role"))
        val content = toolMsg.reqArray("content")
        val block = content[0].reqObj
        val toolResult = block.reqObj("toolResult")
        assertEquals("tu_123", toolResult.string("toolUseId"))
        val resultContent = toolResult.reqArray("content")
        assertEquals("The weather is sunny", resultContent[0].reqObj.string("text"))
    }

    // --- Response parsing ---

    @Test
    fun `parse text response`() {
        val responseJson = """
        {
            "output": {
                "message": {
                    "role": "assistant",
                    "content": [{"text": "Hello there!"}]
                }
            },
            "usage": {"inputTokens": 10, "outputTokens": 5},
            "stopReason": "end_turn",
            "metrics": {"latencyMs": 123}
        }
        """.trimIndent()

        val response = BedrockServiceProvider.parseResponse(responseJson, endpoint)

        assertEquals("Hello there!", response.content)
        assertEquals("anthropic.claude-sonnet-4-20250514-v1:0", response.model)
        assertEquals(10, response.inputTokens)
        assertEquals(5, response.outputTokens)
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `parse reasoning content response`() {
        val responseJson = """
        {
            "output": {
                "message": {
                    "role": "assistant",
                    "content": [
                        {"reasoningContent": {"reasoningText": {"text": "I considered the request.", "signature": "abc"}}},
                        {"text": "Hello there!"}
                    ]
                }
            },
            "usage": {"inputTokens": 10, "outputTokens": 5},
            "stopReason": "end_turn"
        }
        """.trimIndent()

        val response = BedrockServiceProvider.parseResponse(responseJson, endpoint)

        assertEquals("Hello there!", response.content)
        assertEquals("I considered the request.", response.reasoning)
    }

    @Test
    fun `parse tool use response`() {
        val responseJson = """
        {
            "output": {
                "message": {
                    "role": "assistant",
                    "content": [
                        {"toolUse": {"toolUseId": "tu_1", "name": "PersonInfo", "input": {"name": "John", "age": 30}}}
                    ]
                }
            },
            "usage": {"inputTokens": 20, "outputTokens": 15},
            "stopReason": "tool_use"
        }
        """.trimIndent()

        val response = BedrockServiceProvider.parseResponse(responseJson, endpoint)

        assertTrue(response.hasToolUse)
        val toolUse = response.toolUses[0]
        assertEquals("tu_1", toolUse.id)
        assertEquals("PersonInfo", toolUse.name)
        assertEquals("John", toolUse.input.string("name"))
        assertEquals(30, toolUse.input["age"].int)
        assertEquals(FinishReason.TOOL_USE, response.finishReason)
    }

    @Test
    fun `parse mixed text and tool_use response`() {
        val responseJson = """
        {
            "output": {
                "message": {
                    "role": "assistant",
                    "content": [
                        {"text": "Let me look that up."},
                        {"toolUse": {"toolUseId": "tu_1", "name": "search", "input": {"query": "test"}}}
                    ]
                }
            },
            "usage": {"inputTokens": 10, "outputTokens": 20},
            "stopReason": "tool_use"
        }
        """.trimIndent()

        val response = BedrockServiceProvider.parseResponse(responseJson, endpoint)

        assertEquals(2, response.contentParts.size)
        assertEquals("Let me look that up.", response.content)
        assertTrue(response.hasToolUse)
        assertEquals("search", response.toolUses[0].name)
    }

    @Test
    fun `parse response without usage`() {
        val responseJson = """
        {
            "output": {
                "message": {
                    "role": "assistant",
                    "content": [{"text": "Hi"}]
                }
            },
            "stopReason": "end_turn"
        }
        """.trimIndent()

        val response = BedrockServiceProvider.parseResponse(responseJson, endpoint)

        assertEquals("Hi", response.content)
        assertNull(response.inputTokens)
        assertNull(response.outputTokens)
    }

    @Test
    fun `finish reason end_turn maps to STOP`() {
        val responseJson = """
        {
            "output": {"message": {"role": "assistant", "content": [{"text": "Done"}]}},
            "stopReason": "end_turn"
        }
        """.trimIndent()

        val response = BedrockServiceProvider.parseResponse(responseJson, endpoint)
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `finish reason max_tokens maps to MAX_TOKENS`() {
        val responseJson = """
        {
            "output": {"message": {"role": "assistant", "content": [{"text": "Truncated"}]}},
            "stopReason": "max_tokens"
        }
        """.trimIndent()

        val response = BedrockServiceProvider.parseResponse(responseJson, endpoint)
        assertEquals(FinishReason.MAX_TOKENS, response.finishReason)
    }

    @Test
    fun `finish reason tool_use maps to TOOL_USE`() {
        val responseJson = """
        {
            "output": {"message": {"role": "assistant", "content": [{"toolUse": {"toolUseId": "tu_1", "name": "test", "input": {}}}]}},
            "stopReason": "tool_use"
        }
        """.trimIndent()

        val response = BedrockServiceProvider.parseResponse(responseJson, endpoint)
        assertEquals(FinishReason.TOOL_USE, response.finishReason)
    }

    @Test
    fun `finish reason content_filtered maps to CONTENT_FILTER`() {
        val responseJson = """
        {
            "output": {"message": {"role": "assistant", "content": [{"text": ""}]}},
            "stopReason": "content_filtered"
        }
        """.trimIndent()

        val response = BedrockServiceProvider.parseResponse(responseJson, endpoint)
        assertEquals(FinishReason.CONTENT_FILTER, response.finishReason)
    }

    // --- AWS Signature V4 ---

    @Test
    fun `sign produces required headers`() {
        val headers = AwsSignatureV4.sign(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/test/converse",
            headers = mapOf("Content-Type" to "application/json"),
            body = "{}".toByteArray(),
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            region = "us-east-1",
        )

        assertTrue(headers.containsKey("Authorization"))
        assertTrue(headers.containsKey("X-Amz-Date"))
        assertTrue(headers.containsKey("x-amz-content-sha256"))

        val auth = headers["Authorization"]!!
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256"))
        assertTrue(auth.contains("Credential=AKIAIOSFODNN7EXAMPLE/"))
        assertTrue(auth.contains("us-east-1/bedrock/aws4_request"))
        assertTrue(auth.contains("SignedHeaders="))
        assertTrue(auth.contains("Signature="))
    }
}
