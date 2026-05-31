package org.tekfive.konnekt.llm

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.content.ImageSource
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import org.tekfive.konnekt.llm.providers.cohere.CohereServiceProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CohereServiceProviderTest {

    private val endpoint = LlmEndpoint(LlmServiceProviderType.COHERE, LlmModel.COMMAND_R_PLUS, "sk-test")

    // --- Request building ---

    @Test
    fun `basic request JSON`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("command-r-plus", json.string("model"))

        val messages = json.reqArray("messages")
        assertEquals(1, messages.size)
        val msg = messages[0].reqObj
        assertEquals("user", msg.string("role"))
        assertEquals("Hello", msg.string("content"))

        assertTrue(!json.containsKey("temperature"))
        assertTrue(!json.containsKey("max_tokens"))
        assertTrue(!json.containsKey("p"))
        assertTrue(!json.containsKey("k"))
    }

    @Test
    fun `system message included in messages`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.systemMessage("Be helpful"), LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        assertEquals(2, messages.size)

        val sysMsg = messages[0].reqObj
        assertEquals("system", sysMsg.string("role"))
        assertEquals("Be helpful", sysMsg.string("content"))

        val userMsg = messages[1].reqObj
        assertEquals("user", userMsg.string("role"))
        assertEquals("Hello", userMsg.string("content"))
    }

    @Test
    fun `temperature set in request`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            temperature = 0.7,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.7, json["temperature"].double)
    }

    @Test
    fun `max tokens set in request`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            maxTokens = 1000,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(1000, json["max_tokens"].int)
    }

    @Test
    fun `topP mapped to p`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            topP = 0.9,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.9, json["p"].double)
        assertTrue(!json.containsKey("top_p"))
    }

    @Test
    fun `topK mapped to k`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            topK = 50,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(50, json["k"].int)
        assertTrue(!json.containsKey("top_k"))
    }

    @Test
    fun `stop sequences set in request`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            stopSequences = listOf("\n\n", "END"),
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        val stops = json.reqArray("stop_sequences")
        assertEquals(2, stops.size)
    }

    @Test
    fun `presence and frequency penalty set in request`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            presencePenalty = 0.5,
            frequencyPenalty = 0.3,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        assertEquals(0.5, json["presence_penalty"].double)
        assertEquals(0.3, json["frequency_penalty"].double)
    }

    @Test
    fun `tools included in request JSON`() {
        val tools = listOf(
            Tool("search", "Search the web", objectSchema {
                properties { "query" to stringSchema() }
                required("query")
            }),
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Search for cats")),
            endpoint = endpoint,
            tools = tools,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        val toolsArray = json.reqArray("tools")
        assertEquals(1, toolsArray.size)
        val toolObj = toolsArray[0].reqObj
        assertEquals("function", toolObj.string("type"))
        val function = toolObj.reqObj("function")
        assertEquals("search", function.string("name"))
        assertEquals("Search the web", function.string("description"))
        assertNotNull(function.obj("parameters"))
    }

    @Test
    fun `tools not included when responseSchema is set`() {
        val tools = listOf(
            Tool("search", "Search the web", objectSchema {
                properties { "query" to stringSchema() }
            }),
        )
        val schema = objectSchema { title = "Result"; properties { "result" to stringSchema() } }
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Search")),
            endpoint = endpoint,
            tools = tools,
            responseSchema = schema,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        assertTrue(!json.containsKey("tools"))
        assertNotNull(json.obj("response_format"))
    }

    @Test
    fun `structured output uses response_format with json_object`() {
        val schema = objectSchema {
            title = "PersonInfo"
            properties {
                "name" to stringSchema()
            }
            required("name")
        }
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("John")),
            endpoint = endpoint,
            responseSchema = schema,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        val responseFormat = json.reqObj("response_format")
        assertEquals("json_object", responseFormat.string("type"))
        assertNotNull(responseFormat.obj("json_schema"))
    }

    @Test
    fun `tool choice auto`() {
        val tools = listOf(
            Tool("search", "Search", objectSchema { properties { "q" to stringSchema() } }),
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Search")),
            endpoint = endpoint,
            tools = tools,
            toolChoice = ToolChoice.Auto,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("auto", json.string("tool_choice"))
    }

    @Test
    fun `tool choice none`() {
        val tools = listOf(
            Tool("search", "Search", objectSchema { properties { "q" to stringSchema() } }),
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Search")),
            endpoint = endpoint,
            tools = tools,
            toolChoice = ToolChoice.None,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("none", json.string("tool_choice"))
    }

    @Test
    fun `tool choice required`() {
        val tools = listOf(
            Tool("search", "Search", objectSchema { properties { "q" to stringSchema() } }),
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Search")),
            endpoint = endpoint,
            tools = tools,
            toolChoice = ToolChoice.Required,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        assertEquals("required", json.string("tool_choice"))
    }

    @Test
    fun `image url content part`() {
        val message = LlmMessage(PromptRole.USER, listOf(
            LlmContentPart.Text("Describe"),
            LlmContentPart.Image(ImageSource.Url("https://example.com/image.png")),
        ))
        val request = LlmRequest(
            messages = listOf(message),
            endpoint = endpoint,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val content = messages[0].reqObj.reqArray("content")
        assertEquals(2, content.size)

        val textPart = content[0].reqObj
        assertEquals("text", textPart.string("type"))
        assertEquals("Describe", textPart.string("text"))

        val imagePart = content[1].reqObj
        assertEquals("image_url", imagePart.string("type"))
        val imageUrl = imagePart.reqObj("image_url")
        assertEquals("https://example.com/image.png", imageUrl.string("url"))
    }

    @Test
    fun `image base64 content part`() {
        val message = LlmMessage(PromptRole.USER, listOf(
            LlmContentPart.Text("What is this?"),
            LlmContentPart.Image(ImageSource.Base64("image/png", "abc123")),
        ))
        val request = LlmRequest(
            messages = listOf(message),
            endpoint = endpoint,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val content = messages[0].reqObj.reqArray("content")
        val imagePart = content[1].reqObj
        val imageUrl = imagePart.reqObj("image_url")
        assertEquals("data:image/png;base64,abc123", imageUrl.string("url"))
    }

    @Test
    fun `tool use serialized in assistant message`() {
        val toolUseInput = JsonObject(mapOf("location" to "NYC"))
        val message = LlmMessage(PromptRole.ASSISTANT, listOf(
            LlmContentPart.ToolUse("call-1", "get_weather", toolUseInput),
        ))
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather?"), message),
            endpoint = endpoint,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val assistantMsg = messages[1].reqObj
        assertEquals("assistant", assistantMsg.string("role"))
        val toolCalls = assistantMsg.reqArray("tool_calls")
        assertEquals(1, toolCalls.size)
        val toolCall = toolCalls[0].reqObj
        assertEquals("call-1", toolCall.string("id"))
        assertEquals("function", toolCall.string("type"))
        val function = toolCall.reqObj("function")
        assertEquals("get_weather", function.string("name"))
    }

    @Test
    fun `tool result serialized as tool message`() {
        val message = LlmMessage.toolResultMessage("call-1", "Sunny, 72F")
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather?"), message),
            endpoint = endpoint,
        )
        val json = CohereServiceProvider.buildRequestJson(request, endpoint)

        val messages = json.reqArray("messages")
        val toolMsg = messages[1].reqObj
        assertEquals("tool", toolMsg.string("role"))
        assertEquals("call-1", toolMsg.string("tool_call_id"))
        assertEquals("Sunny, 72F", toolMsg.string("content"))
    }

    // --- Response parsing ---

    @Test
    fun `parse response with text`() {
        val responseJson = """
        {
            "id": "resp-123",
            "message": {
                "role": "assistant",
                "content": [{"type": "text", "text": "Hello there!"}]
            },
            "finish_reason": "COMPLETE",
            "usage": {
                "billed_units": {"input_tokens": 10, "output_tokens": 5},
                "tokens": {"input_tokens": 10, "output_tokens": 5}
            },
            "model": "command-r-plus"
        }
        """.trimIndent()

        val response = CohereServiceProvider.parseResponse(responseJson, endpoint)

        assertEquals("Hello there!", response.content)
        assertEquals("command-r-plus", response.model)
        assertEquals(10, response.inputTokens)
        assertEquals(5, response.outputTokens)
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `parse response with tool calls`() {
        val responseJson = """
        {
            "id": "resp-456",
            "message": {
                "role": "assistant",
                "tool_calls": [
                    {
                        "id": "call-1",
                        "type": "function",
                        "function": {
                            "name": "search",
                            "arguments": "{\"query\": \"cats\"}"
                        }
                    }
                ]
            },
            "finish_reason": "TOOL_CALL",
            "usage": {
                "tokens": {"input_tokens": 15, "output_tokens": 8}
            },
            "model": "command-r-plus"
        }
        """.trimIndent()

        val response = CohereServiceProvider.parseResponse(responseJson, endpoint)

        assertTrue(response.hasToolUse)
        assertEquals(1, response.toolUses.size)
        val toolUse = response.toolUses[0]
        assertEquals("call-1", toolUse.id)
        assertEquals("search", toolUse.name)
        assertEquals("cats", toolUse.input.string("query"))
        assertEquals(FinishReason.TOOL_USE, response.finishReason)
    }

    @Test
    fun `parse response without usage`() {
        val responseJson = """
        {
            "id": "resp-789",
            "message": {
                "role": "assistant",
                "content": [{"type": "text", "text": "Hi"}]
            },
            "finish_reason": "COMPLETE",
            "model": "command-r-plus"
        }
        """.trimIndent()

        val response = CohereServiceProvider.parseResponse(responseJson, endpoint)

        assertEquals("Hi", response.content)
        assertNull(response.inputTokens)
        assertNull(response.outputTokens)
    }

    // --- Finish reason mapping ---

    @Test
    fun `mapFinishReason COMPLETE to STOP`() {
        assertEquals(FinishReason.STOP, CohereServiceProvider.mapFinishReason("COMPLETE"))
    }

    @Test
    fun `mapFinishReason MAX_TOKENS`() {
        assertEquals(FinishReason.MAX_TOKENS, CohereServiceProvider.mapFinishReason("MAX_TOKENS"))
    }

    @Test
    fun `mapFinishReason TOOL_CALL to TOOL_USE`() {
        assertEquals(FinishReason.TOOL_USE, CohereServiceProvider.mapFinishReason("TOOL_CALL"))
    }

    @Test
    fun `mapFinishReason unknown defaults to STOP`() {
        assertEquals(FinishReason.STOP, CohereServiceProvider.mapFinishReason("OTHER"))
    }

    // --- Embedding response parsing ---

    @Test
    fun `parse embedding response`() {
        val responseJson = """
        {
            "id": "emb-123",
            "embeddings": {
                "float": [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]]
            },
            "meta": {
                "billed_units": {"input_tokens": 5}
            }
        }
        """.trimIndent()

        val response = CohereServiceProvider.parseEmbeddingResponse(responseJson, "embed-v4.0")

        assertEquals(2, response.embeddings.size)
        assertEquals(3, response.embeddings[0].size)
        assertEquals(0.1f, response.embeddings[0][0], 0.01f)
        assertEquals(0.2f, response.embeddings[0][1], 0.01f)
        assertEquals(0.3f, response.embeddings[0][2], 0.01f)
        assertEquals(0.4f, response.embeddings[1][0], 0.01f)
        assertEquals("embed-v4.0", response.model)
        assertEquals(5, response.inputTokens)
    }

    @Test
    fun `parse embedding response without billing info`() {
        val responseJson = """
        {
            "id": "emb-456",
            "embeddings": {
                "float": [[0.1, 0.2]]
            }
        }
        """.trimIndent()

        val response = CohereServiceProvider.parseEmbeddingResponse(responseJson, "embed-v4.0")

        assertEquals(1, response.embeddings.size)
        assertNull(response.inputTokens)
    }
}
