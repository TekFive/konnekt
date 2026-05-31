package org.tekfive.konnekt.llm

import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.schema.integerSchema
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.batch.MLBatchItem
import org.tekfive.konnekt.llm.batch.MLBatchStatus
import org.tekfive.konnekt.llm.content.DocumentSource
import org.tekfive.konnekt.llm.content.ImageSource
import org.tekfive.konnekt.llm.providers.gemini.GeminiServiceProvider
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiServiceProviderTest {

    private val endpoint = LlmEndpoint(LlmServiceProviderType.GOOGLE, LlmModel.GEMINI_2_FLASH, "sk-test")
    private val proEndpoint = LlmEndpoint(LlmServiceProviderType.GOOGLE, LlmModel.GEMINI_2_PRO, "sk-test")

    // --- Chat request building ---

    @Test
    fun `basic request JSON`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        assertTrue(!json.containsKey("systemInstruction"))

        val contents = json.reqArray("contents")
        assertEquals(1, contents.size)
        val content = contents[0].reqObj
        assertEquals("user", content.string("role"))
        val parts = content.reqArray("parts")
        val part = parts[0].reqObj
        assertEquals("Hello", part.string("text"))

        val config = json.reqObj("generationConfig")
        assertTrue(!config.containsKey("temperature"))
    }

    @Test
    fun `system message extracted to systemInstruction`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.systemMessage("Be helpful"), LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val sysInstruction = json.reqObj("systemInstruction")
        val parts = sysInstruction.reqArray("parts")
        val part = parts[0].reqObj
        assertEquals("Be helpful", part.string("text"))

        val contents = json.reqArray("contents")
        assertEquals(1, contents.size)
    }

    @Test
    fun `assistant role mapped to model`() {
        val request = LlmRequest(
            messages = listOf(
                 LlmMessage.userMessage("Hello"),
                  LlmMessage.assistantMessage("Hi there"),
                 LlmMessage.userMessage("How are you?"),
            ),
            endpoint = endpoint,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val contents = json.reqArray("contents")
        assertEquals(3, contents.size)

        val assistantContent = contents[1].reqObj
        assertEquals("model", assistantContent.string("role"))
    }

    @Test
    fun `tool role mapped to user`() {
        val request = LlmRequest(
            messages = listOf(
                 LlmMessage.userMessage("What is the weather?"),
                LlmMessage.toolResultMessage("call-1", "Sunny, 72F"),
            ),
            endpoint = endpoint,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val contents = json.reqArray("contents")
        assertEquals(2, contents.size)

        val toolContent = contents[1].reqObj
        assertEquals("user", toolContent.string("role"))
    }

    @Test
    fun `max tokens set in generation config`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = proEndpoint,
            maxTokens = 2000,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val config = json.reqObj("generationConfig")
        assertEquals(2000, config["maxOutputTokens"].int)
    }

    @Test
    fun `temperature set in generation config`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            temperature = 0.5,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val config = json.reqObj("generationConfig")
        assertEquals(0.5, config["temperature"].double)
    }

    @Test
    fun `topP topK stopSequences presencePenalty frequencyPenalty in generation config`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            topP = 0.9,
            topK = 40,
            stopSequences = listOf("END", "STOP"),
            presencePenalty = 0.5,
            frequencyPenalty = 0.3,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val config = json.reqObj("generationConfig")
        assertEquals(0.9, config["topP"].double)
        assertEquals(40, config["topK"].int)
        val stops = config.reqArray("stopSequences")
        assertEquals(2, stops.size)
        assertEquals(0.5, config["presencePenalty"].double)
        assertEquals(0.3, config["frequencyPenalty"].double)
    }

    @Test
    fun `return reasoning text includes thoughts`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hi")),
            endpoint = endpoint,
            reasoningEffort = LlmReasoningEffort.MEDIUM,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val thinkingConfig = json.reqObj("generationConfig").reqObj("thinkingConfig")
        assertEquals(true, thinkingConfig["includeThoughts"].boolean)
        assertEquals(4096, thinkingConfig["thinkingBudget"].int)
    }

    @Test
    fun `structured output uses responseSchema in generation config`() {
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
        val json = GeminiServiceProvider.buildRequestJson(request)

        val config = json.reqObj("generationConfig")
        assertEquals("application/json", config.string("responseMimeType"))
        assertNotNull(config.obj("responseSchema"))

        val responseSchema = config.reqObj("responseSchema")
        assertEquals("object", responseSchema.string("type"))
    }

    @Test
    fun `no responseSchema when no schema`() {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Hello")),
            endpoint = endpoint,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val config = json.reqObj("generationConfig")
        assertTrue(!config.containsKey("responseMimeType"))
        assertTrue(!config.containsKey("responseSchema"))
    }

    // --- Tools in request ---

    @Test
    fun `tools included in request JSON`() {
        val tools = listOf(
            Tool("get_weather", "Get the weather", objectSchema {
                properties { "location" to stringSchema() }
                required("location")
            }),
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("What is the weather?")),
            endpoint = endpoint,
            tools = tools,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val toolsArray = json.reqArray("tools")
        assertEquals(1, toolsArray.size)
        val toolObj = toolsArray[0].reqObj
        val declarations = toolObj.reqArray("functionDeclarations")
        assertEquals(1, declarations.size)
        val decl = declarations[0].reqObj
        assertEquals("get_weather", decl.string("name"))
        assertEquals("Get the weather", decl.string("description"))
        assertNotNull(decl.obj("parameters"))
    }

    @Test
    fun `tools not included when responseSchema is set`() {
        val tools = listOf(
            Tool("get_weather", "Get the weather", objectSchema {
                properties { "location" to stringSchema() }
                required("location")
            }),
        )
        val schema = objectSchema { title = "Result"; properties { "result" to stringSchema() } }
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("What is the weather?")),
            endpoint = endpoint,
            tools = tools,
            responseSchema = schema,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        assertTrue(!json.containsKey("tools"))
    }

    @Test
    fun `tool choice Auto`() {
        val tools = listOf(
            Tool("get_weather", "Get the weather", objectSchema { properties { "location" to stringSchema() } }),
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather?")),
            endpoint = endpoint,
            tools = tools,
            toolChoice = ToolChoice.Auto,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val toolConfig = json.reqObj("toolConfig")
        val fcc = toolConfig.reqObj("functionCallingConfig")
        assertEquals("AUTO", fcc.string("mode"))
    }

    @Test
    fun `tool choice None`() {
        val tools = listOf(
            Tool("get_weather", "Get the weather", objectSchema { properties { "location" to stringSchema() } }),
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather?")),
            endpoint = endpoint,
            tools = tools,
            toolChoice = ToolChoice.None,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val toolConfig = json.reqObj("toolConfig")
        val fcc = toolConfig.reqObj("functionCallingConfig")
        assertEquals("NONE", fcc.string("mode"))
    }

    @Test
    fun `tool choice Required`() {
        val tools = listOf(
            Tool("get_weather", "Get the weather", objectSchema { properties { "location" to stringSchema() } }),
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather?")),
            endpoint = endpoint,
            tools = tools,
            toolChoice = ToolChoice.Required,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val toolConfig = json.reqObj("toolConfig")
        val fcc = toolConfig.reqObj("functionCallingConfig")
        assertEquals("ANY", fcc.string("mode"))
    }

    @Test
    fun `tool choice Specific`() {
        val tools = listOf(
            Tool("get_weather", "Get the weather", objectSchema { properties { "location" to stringSchema() } }),
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather?")),
            endpoint = endpoint,
            tools = tools,
            toolChoice = ToolChoice.Specific("get_weather"),
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val toolConfig = json.reqObj("toolConfig")
        val fcc = toolConfig.reqObj("functionCallingConfig")
        assertEquals("ANY", fcc.string("mode"))
        val allowed = fcc.reqArray("allowedFunctionNames")
        assertEquals(1, allowed.size)
        assertEquals("get_weather", allowed[0].string)
    }

    // --- Multimodal content parts ---

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
        val json = GeminiServiceProvider.buildRequestJson(request)

        val contents = json.reqArray("contents")
        val parts = contents[0].reqObj.reqArray("parts")
        assertEquals(2, parts.size)

        val textPart = parts[0].reqObj
        assertEquals("What is this?", textPart.string("text"))

        val imagePart = parts[1].reqObj
        val inlineData = imagePart.reqObj("inlineData")
        assertEquals("image/png", inlineData.string("mimeType"))
        assertEquals("abc123", inlineData.string("data"))
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
        val json = GeminiServiceProvider.buildRequestJson(request)

        val parts = json.reqArray("contents")[0].reqObj.reqArray("parts")
        val imagePart = parts[1].reqObj
        val fileData = imagePart.reqObj("fileData")
        assertEquals("image/*", fileData.string("mimeType"))
        assertEquals("https://example.com/image.png", fileData.string("fileUri"))
    }

    @Test
    fun `document base64 content part`() {
        val message = LlmMessage(PromptRole.USER, listOf(
            LlmContentPart.Text("Summarize"),
            LlmContentPart.Document(DocumentSource.Base64("application/pdf", "pdf123")),
        ))
        val request = LlmRequest(
            messages = listOf(message),
            endpoint = endpoint,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val parts = json.reqArray("contents")[0].reqObj.reqArray("parts")
        val docPart = parts[1].reqObj
        val inlineData = docPart.reqObj("inlineData")
        assertEquals("application/pdf", inlineData.string("mimeType"))
        assertEquals("pdf123", inlineData.string("data"))
    }

    @Test
    fun `tool use content part serialized as functionCall`() {
        val toolUseInput = JsonObject(mapOf("location" to "NYC"))
        val message = LlmMessage(PromptRole.ASSISTANT, listOf(
            LlmContentPart.ToolUse("call-1", "get_weather", toolUseInput),
        ))
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather?"), message),
            endpoint = endpoint,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val contents = json.reqArray("contents")
        val assistantParts = contents[1].reqObj.reqArray("parts")
        val fcPart = assistantParts[0].reqObj
        val functionCall = fcPart.reqObj("functionCall")
        assertEquals("get_weather", functionCall.string("name"))
        assertNotNull(functionCall.obj("args"))
    }

    @Test
    fun `tool result content part serialized as functionResponse`() {
        val message = LlmMessage.toolResultMessage("call-1", "Sunny", toolName = "get_weather")
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage("Weather?"), message),
            endpoint = endpoint,
        )
        val json = GeminiServiceProvider.buildRequestJson(request)

        val contents = json.reqArray("contents")
        val toolParts = contents[1].reqObj.reqArray("parts")
        val frPart = toolParts[0].reqObj
        val functionResponse = frPart.reqObj("functionResponse")
        assertEquals("get_weather", functionResponse.string("name"))
        assertEquals("call-1", functionResponse.string("id"))
        val responseObj = functionResponse.reqObj("response")
        assertEquals("Sunny", responseObj.string("content"))
    }

    // --- Chat response parsing ---

    @Test
    fun `parse response with text`() {
        val responseJson = """
        {
            "candidates": [
                {
                    "content": {
                        "parts": [{"text": "Hello there!"}],
                        "role": "model"
                    },
                    "finishReason": "STOP"
                }
            ],
            "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5, "totalTokenCount": 15}
        }
        """.trimIndent()

        val response = GeminiServiceProvider.parseResponse(responseJson, "gemini-2.0-flash", endpoint)

        assertEquals("Hello there!", response.content)
        assertEquals("gemini-2.0-flash", response.model)
        assertEquals(10, response.inputTokens)
        assertEquals(5, response.outputTokens)
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `parse response with thought text`() {
        val responseJson = """
        {
            "candidates": [
                {
                    "content": {
                        "parts": [
                            {"thought": true, "text": "I considered the request."},
                            {"text": "Hello there!"}
                        ],
                        "role": "model"
                    },
                    "finishReason": "STOP"
                }
            ],
            "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5}
        }
        """.trimIndent()

        val response = GeminiServiceProvider.parseResponse(responseJson, "gemini-2.0-flash", endpoint)

        assertEquals("Hello there!", response.content)
        assertEquals("I considered the request.", response.reasoning)
    }

    @Test
    fun `parse response without usage metadata`() {
        val responseJson = """
        {
            "candidates": [
                {
                    "content": {
                        "parts": [{"text": "Hi"}],
                        "role": "model"
                    },
                    "finishReason": "STOP"
                }
            ]
        }
        """.trimIndent()

        val response = GeminiServiceProvider.parseResponse(responseJson, "gemini-2.0-flash", endpoint)

        assertEquals("Hi", response.content)
        assertNull(response.inputTokens)
        assertNull(response.outputTokens)
    }

    @Test
    fun `parse response with functionCall`() {
        val responseJson = """
        {
            "candidates": [
                {
                    "content": {
                        "parts": [{"functionCall": {"id": "call-1", "name": "get_weather", "args": {"location": "NYC"}}}],
                        "role": "model"
                    },
                    "finishReason": "STOP"
                }
            ],
            "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5}
        }
        """.trimIndent()

        val response = GeminiServiceProvider.parseResponse(responseJson, "gemini-2.0-flash", endpoint)

        assertTrue(response.hasToolUse)
        assertEquals(1, response.toolUses.size)
        val toolUse = response.toolUses[0]
        assertEquals("call-1", toolUse.id)
        assertEquals("get_weather", toolUse.name)
        assertEquals("NYC", toolUse.input.string("location"))
    }

    @Test
    fun `parse response with text and functionCall`() {
        val responseJson = """
        {
            "candidates": [
                {
                    "content": {
                        "parts": [
                            {"text": "Let me check."},
                            {"functionCall": {"name": "search", "args": {"q": "test"}}}
                        ],
                        "role": "model"
                    },
                    "finishReason": "STOP"
                }
            ]
        }
        """.trimIndent()

        val response = GeminiServiceProvider.parseResponse(responseJson, "gemini-2.0-flash", endpoint)

        assertEquals("Let me check.", response.content)
        assertTrue(response.hasToolUse)
        assertEquals("search", response.toolUses[0].name)
    }

    // --- FinishReason mapping ---

    @Test
    fun `mapFinishReason STOP`() {
        assertEquals(FinishReason.STOP, GeminiServiceProvider.mapFinishReason("STOP"))
    }

    @Test
    fun `mapFinishReason MAX_TOKENS`() {
        assertEquals(FinishReason.MAX_TOKENS, GeminiServiceProvider.mapFinishReason("MAX_TOKENS"))
    }

    @Test
    fun `mapFinishReason SAFETY`() {
        assertEquals(FinishReason.CONTENT_FILTER, GeminiServiceProvider.mapFinishReason("SAFETY"))
    }

    @Test
    fun `mapFinishReason null`() {
        assertNull(GeminiServiceProvider.mapFinishReason(null))
    }

    @Test
    fun `mapFinishReason unknown`() {
        assertNull(GeminiServiceProvider.mapFinishReason("OTHER"))
    }

    // Provider type test removed: circular initialization between MLProvider enum
    // and service provider objects makes direct .provider access unreliable in tests.
    // The mapping is verified via MLProvider.forModel() tests in MLModelTest.

    // --- Embeddings ---

    @Test
    fun `parse embedding response`() {
        val responseJson = """
        {
            "embeddings": [
                {"values": [0.1, 0.2, 0.3]},
                {"values": [0.4, 0.5, 0.6]}
            ]
        }
        """.trimIndent()

        val response = GeminiServiceProvider.parseEmbeddingResponse(responseJson, "text-embedding-004")

        assertEquals(2, response.embeddings.size)
        assertEquals(3, response.embeddings[0].size)
        assertEquals(0.1f, response.embeddings[0][0], 0.01f)
        assertEquals(0.2f, response.embeddings[0][1], 0.01f)
        assertEquals(0.3f, response.embeddings[0][2], 0.01f)
        assertEquals(0.4f, response.embeddings[1][0], 0.01f)
        assertEquals("text-embedding-004", response.model)
    }

    // --- Batch request building ---

    @Test
    fun `batch request JSON has correct structure`() {
        val items = listOf(
            MLBatchItem("req-1", LlmRequest(listOf(LlmMessage.userMessage("Hello")), endpoint)),
            MLBatchItem("req-2", LlmRequest(listOf(LlmMessage.systemMessage("Be brief"), LlmMessage.userMessage("Hi")), endpoint)),
        )

        val json = GeminiServiceProvider.buildBatchRequestJson(items, LlmModel.GEMINI_2_FLASH)

        val batch = json.reqObj("batch")
        val inputConfig = batch.reqObj("input_config")
        val requestsWrapper = inputConfig.reqObj("requests")
        val requests = requestsWrapper.reqArray("requests")
        assertEquals(2, requests.size)

        val first = requests[0].reqObj
        val firstMetadata = first.reqObj("metadata")
        assertEquals("req-1", firstMetadata.string("key"))
        val firstRequest = first.reqObj("request")
        assertEquals("models/gemini-2.0-flash", firstRequest.string("model"))

        val second = requests[1].reqObj
        val secondMetadata = second.reqObj("metadata")
        assertEquals("req-2", secondMetadata.string("key"))
        val secondRequest = second.reqObj("request")
        assertEquals("models/gemini-2.0-flash", secondRequest.string("model"))
        assertNotNull(secondRequest.obj("systemInstruction"))
    }

    @Test
    fun `batch create URL targets model specific endpoint`() {
        val url = GeminiServiceProvider.buildBatchCreateUrl(
            baseUrl = "https://generativelanguage.googleapis.com",
            modelId = LlmModel.GEMINI_2_FLASH,
            apiKey = "secret",
        )

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:batchGenerateContent?key=secret",
            url,
        )
    }

    // --- Batch status parsing ---

    @Test
    fun `parse batch status PENDING maps to IN_PROGRESS`() {
        assertEquals(MLBatchStatus.IN_PROGRESS, GeminiServiceProvider.parseBatchStatus("JOB_STATE_PENDING"))
    }

    @Test
    fun `parse batch status BATCH_STATE_PENDING maps to IN_PROGRESS`() {
        assertEquals(MLBatchStatus.IN_PROGRESS, GeminiServiceProvider.parseBatchStatus("BATCH_STATE_PENDING"))
    }

    @Test
    fun `parse batch status RUNNING maps to IN_PROGRESS`() {
        assertEquals(MLBatchStatus.IN_PROGRESS, GeminiServiceProvider.parseBatchStatus("JOB_STATE_RUNNING"))
    }

    @Test
    fun `parse batch status BATCH_STATE_RUNNING maps to IN_PROGRESS`() {
        assertEquals(MLBatchStatus.IN_PROGRESS, GeminiServiceProvider.parseBatchStatus("BATCH_STATE_RUNNING"))
    }

    @Test
    fun `parse batch status SUCCEEDED maps to COMPLETED`() {
        assertEquals(MLBatchStatus.COMPLETED, GeminiServiceProvider.parseBatchStatus("JOB_STATE_SUCCEEDED"))
    }

    @Test
    fun `parse batch status BATCH_STATE_SUCCEEDED maps to COMPLETED`() {
        assertEquals(MLBatchStatus.COMPLETED, GeminiServiceProvider.parseBatchStatus("BATCH_STATE_SUCCEEDED"))
    }

    @Test
    fun `parse batch status FAILED`() {
        assertEquals(MLBatchStatus.FAILED, GeminiServiceProvider.parseBatchStatus("JOB_STATE_FAILED"))
    }

    @Test
    fun `parse batch status BATCH_STATE_FAILED`() {
        assertEquals(MLBatchStatus.FAILED, GeminiServiceProvider.parseBatchStatus("BATCH_STATE_FAILED"))
    }

    @Test
    fun `parse batch status CANCELLED`() {
        assertEquals(MLBatchStatus.CANCELLED, GeminiServiceProvider.parseBatchStatus("JOB_STATE_CANCELLED"))
    }

    @Test
    fun `parse batch status BATCH_STATE_CANCELLED`() {
        assertEquals(MLBatchStatus.CANCELLED, GeminiServiceProvider.parseBatchStatus("BATCH_STATE_CANCELLED"))
    }

    @Test
    fun `parse batch status EXPIRED`() {
        assertEquals(MLBatchStatus.EXPIRED, GeminiServiceProvider.parseBatchStatus("JOB_STATE_EXPIRED"))
    }

    @Test
    fun `parse batch status BATCH_STATE_EXPIRED`() {
        assertEquals(MLBatchStatus.EXPIRED, GeminiServiceProvider.parseBatchStatus("BATCH_STATE_EXPIRED"))
    }

    @Test
    fun `extract batch state from operation metadata`() {
        val json = JsonObject(
            mapOf(
                "name" to "operations/123",
                "metadata" to JsonObject(
                    mapOf(
                        "state" to "JOB_STATE_RUNNING",
                    )
                ),
            )
        )

        assertEquals("JOB_STATE_RUNNING", GeminiServiceProvider.extractBatchState(json))
    }

    // --- Batch results parsing ---

    @Test
    fun `parse inlined responses`() {
        val responses = JsonArray(listOf(
            JsonObject(mapOf(
                "key" to "req-1",
                "response" to JsonObject(mapOf(
                    "candidates" to JsonArray(listOf(
                        JsonObject(mapOf(
                            "content" to JsonObject(mapOf(
                                "parts" to JsonArray(listOf(JsonObject(mapOf("text" to "Hello!")))),
                                "role" to "model",
                            )),
                            "finishReason" to "STOP",
                        )),
                    )),
                    "usageMetadata" to JsonObject(mapOf("promptTokenCount" to 10, "candidatesTokenCount" to 3)),
                    "modelVersion" to "gemini-2.0-flash",
                )),
            )),
            JsonObject(mapOf(
                "key" to "req-2",
                "response" to JsonObject(mapOf(
                    "candidates" to JsonArray(listOf(
                        JsonObject(mapOf(
                            "content" to JsonObject(mapOf(
                                "parts" to JsonArray(listOf(JsonObject(mapOf("text" to "Hi!")))),
                                "role" to "model",
                            )),
                            "finishReason" to "STOP",
                        )),
                    )),
                    "modelVersion" to "gemini-2.0-pro",
                )),
            )),
        ))

        val results = GeminiServiceProvider.parseInlinedResponses(responses)

        assertEquals(2, results.size)

        val first = results[0]
        assertEquals("req-1", first.id)
        assertTrue(first.isSuccess)
        assertEquals("Hello!", first.response!!.content)
        assertEquals("gemini-2.0-flash", first.response!!.model)
        assertEquals(10, first.response!!.inputTokens)

        val second = results[1]
        assertEquals("req-2", second.id)
        assertTrue(second.isSuccess)
        assertEquals("Hi!", second.response!!.content)
    }

    @Test
    fun `parse inlined responses with error`() {
        val responses = JsonArray(listOf(
            JsonObject(mapOf(
                "key" to "req-1",
                "response" to null,
                "error" to JsonObject(mapOf("message" to "Invalid model")),
            )),
        ))

        val results = GeminiServiceProvider.parseInlinedResponses(responses)

        assertEquals(1, results.size)
        val result = results[0]
        assertEquals("req-1", result.id)
        assertNull(result.response)
        assertEquals("Invalid model", result.error)
    }

    @Test
    fun `parse inlined responses from operation response wrapper`() {
        val operationJson = JsonObject(
            mapOf(
                "response" to JsonObject(
                    mapOf(
                        "inlinedResponses" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "metadata" to JsonObject(mapOf("key" to "req-1")),
                                        "response" to JsonObject(
                                            mapOf(
                                                "candidates" to JsonArray(
                                                    listOf(
                                                        JsonObject(
                                                            mapOf(
                                                                "content" to JsonObject(
                                                                    mapOf(
                                                                        "parts" to JsonArray(listOf(JsonObject(mapOf("text" to "Hello!")))),
                                                                        "role" to "model",
                                                                    )
                                                                ),
                                                                "finishReason" to "STOP",
                                                            )
                                                        ),
                                                    )
                                                ),
                                                "modelVersion" to "gemini-2.0-flash",
                                            )
                                        ),
                                    )
                                ),
                            )
                        ),
                    )
                ),
            )
        )

        val response = operationJson.reqObj("response")
        val results = GeminiServiceProvider.parseInlinedResponses(response.reqArray("inlinedResponses"))

        assertEquals(1, results.size)
        assertEquals("req-1", results[0].id)
        assertEquals("Hello!", results[0].response!!.content)
    }

    @Test
    fun `top level dest with inlined responses is readable`() {
        val json = JsonObject(
            mapOf(
                "dest" to JsonObject(
                    mapOf(
                        "inlinedResponses" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "metadata" to JsonObject(mapOf("key" to "req-1")),
                                        "response" to JsonObject(
                                            mapOf(
                                                "candidates" to JsonArray(
                                                    listOf(
                                                        JsonObject(
                                                            mapOf(
                                                                "content" to JsonObject(
                                                                    mapOf(
                                                                        "parts" to JsonArray(listOf(JsonObject(mapOf("text" to "Done")))),
                                                                        "role" to "model",
                                                                    )
                                                                ),
                                                                "finishReason" to "STOP",
                                                            )
                                                        ),
                                                    )
                                                ),
                                                "modelVersion" to "gemini-2.0-flash",
                                            )
                                        ),
                                    )
                                ),
                            )
                        ),
                    )
                ),
            )
        )

        val dest = json.reqObj("dest")
        val results = GeminiServiceProvider.parseInlinedResponses(dest.reqArray("inlinedResponses"))

        assertEquals(1, results.size)
        assertEquals("req-1", results[0].id)
        assertEquals("Done", results[0].response!!.content)
    }

    @Test
    fun `extract inlined responses from wrapper object`() {
        val json = JsonObject(
            mapOf(
                "inlinedResponses" to JsonObject(
                    mapOf(
                        "inlinedResponses" to JsonArray(
                            listOf(
                                JsonObject(mapOf("key" to "req-1")),
                            )
                        ),
                    )
                ),
            )
        )

        val responses = GeminiServiceProvider.extractInlinedResponses(json)

        assertNotNull(responses)
        assertEquals(1, responses.size)
        assertEquals("req-1", responses[0].reqObj.string("key"))
    }

    @Test
    fun `extract batch result key prefers metadata key`() {
        val json = JsonObject(
            mapOf(
                "metadata" to JsonObject(mapOf("key" to "chat-1")),
            )
        )

        assertEquals("chat-1", GeminiServiceProvider.extractBatchResultKey(json))
    }

    @Test
    fun `parse batch results JSONL`() {
        val jsonl = """
            {"key":"req-1","response":{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3},"modelVersion":"gemini-2.0-flash"}}
            {"key":"req-2","response":{"candidates":[{"content":{"parts":[{"text":"Hi!"}],"role":"model"},"finishReason":"STOP"}],"modelVersion":"gemini-2.0-pro"}}
        """.trimIndent()

        val results = GeminiServiceProvider.parseBatchResultsJsonl(jsonl)

        assertEquals(2, results.size)
        assertTrue(results[0].isSuccess)
        assertEquals("Hello!", results[0].response!!.content)
        assertTrue(results[1].isSuccess)
        assertEquals("Hi!", results[1].response!!.content)
    }
}
