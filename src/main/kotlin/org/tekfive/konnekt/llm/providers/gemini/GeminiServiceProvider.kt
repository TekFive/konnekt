package org.tekfive.konnekt.llm.providers.gemini

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.konnekt.llm.batch.BatchProvider
import org.tekfive.konnekt.llm.ChatProvider
import org.tekfive.konnekt.llm.LlmContentPart
import org.tekfive.konnekt.llm.content.DocumentSource
import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.embedding.EmbeddingProvider
import org.tekfive.konnekt.llm.embedding.EmbeddingRequest
import org.tekfive.konnekt.llm.embedding.EmbeddingResponse
import org.tekfive.konnekt.llm.FinishReason
import org.tekfive.konnekt.llm.content.ImageSource
import org.tekfive.konnekt.llm.batch.MLBatchItem
import org.tekfive.konnekt.llm.batch.MLBatchResult
import org.tekfive.konnekt.llm.batch.MLBatchStatus
import org.tekfive.konnekt.llm.LlmException
import org.tekfive.konnekt.llm.LlmRequest
import org.tekfive.konnekt.llm.LlmResponse
import org.tekfive.konnekt.llm.LlmReasoningEffort
import org.tekfive.konnekt.llm.PromptRole
import org.tekfive.konnekt.llm.stream.StreamListener
import org.tekfive.konnekt.llm.stream.StreamingProvider
import org.tekfive.konnekt.llm.content.ToolChoice
import org.tekfive.konnekt.llm.utils.OkHttpClientPool
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ML service provider for the Google Gemini API.
 *
 * System instructions are sent in the `systemInstruction` field.
 * The assistant role is mapped to Gemini's `model` role.
 * Structured output uses `responseMimeType` and `responseSchema` in generation config.
 * Batch support uses the Gemini Batch API (`/v1beta/batches`) with inline requests.
 */
object GeminiServiceProvider : ChatProvider, StreamingProvider, BatchProvider, EmbeddingProvider {

    private val clientPool = OkHttpClientPool()

    // --- Chat ---

    override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val modelId = endpoint.model ?: ""
        val body = buildRequestJson(request)
        val url = "$baseUrl/v1beta/models/$modelId:generateContent?key=$apiKey"

        val httpRequest = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val client = clientPool.getClient(endpoint)
        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Gemini API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("Gemini API error ${response.code}: $responseBody")
        }

        return parseResponse(responseBody, modelId, endpoint)
    }

    // --- Streaming ---

    override fun chatStream(request: LlmRequest, endpoint: LlmEndpoint, listener: StreamListener) {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val modelId = endpoint.model ?: ""
        val body = buildRequestJson(request)
        val url = "$baseUrl/v1beta/models/$modelId:streamGenerateContent?key=$apiKey&alt=sse"

        val httpRequest = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val client = clientPool.getClient(endpoint)
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body
                ?: throw LlmException("Empty response from Gemini streaming API")

            if (response.code == 429) {
                val errorBody = responseBody.string()
                throw LlmException(
                    "${type.displayName} rate limited: $errorBody",
                    isRateLimited = true,
                )
            }

            if (!response.isSuccessful) {
                val errorBody = responseBody.string()
                throw LlmException("Gemini streaming API error ${response.code}: $errorBody")
            }

            val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))
            val allParts = mutableListOf<LlmContentPart>()
            val reasoningBuilder = StringBuilder()
            var lastJson: JsonObject? = null

            reader.use {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (!trimmed.startsWith("data: ")) continue
                    val data = trimmed.removePrefix("data: ").trim()
                    if (data.isEmpty()) continue

                    val eventJson = data.asRequiredJsonObject()
                    lastJson = eventJson

                    val candidates = eventJson.array("candidates") ?: continue
                    if (candidates.size == 0) continue
                    val candidate = candidates[0].reqObj
                    val content = candidate.obj("content") ?: continue
                    val parts = content.array("parts") ?: continue

                    for (i in 0 until parts.size) {
                        val part = parts[i].reqObj
                        val text = part.string("text")
                        if (text != null) {
                            val thought = part.boolean("thought") ?: false
                            if (thought) {
                                reasoningBuilder.append(text)
                                listener.onReasoningToken(text)
                            } else {
                                listener.onToken(text)
                                allParts.add(LlmContentPart.Text(text))
                            }
                        }
                        val functionCall = part.obj("functionCall")
                        if (functionCall != null) {
                            val id = functionCall.string("id") ?: ""
                            val name = functionCall.reqString("name")
                            val args = functionCall.obj("args") ?: JsonObject()
                            allParts.add(LlmContentPart.ToolUse(id, name, args))
                        }
                    }
                }
            }

            val usageMetadata = lastJson?.obj("usageMetadata")
            val finishReason = lastJson?.let { json ->
                val candidates = json.array("candidates")
                candidates?.let {
                    if (it.size > 0) it[0].reqObj.string("finishReason") else null
                }
            }

            val llmResponse = LlmResponse(
                contentParts = allParts,
                model = modelId,
                inputTokens = usageMetadata?.get("promptTokenCount")?.int,
                outputTokens = usageMetadata?.get("candidatesTokenCount")?.int,
                finishReason = mapFinishReason(finishReason),
                endpoint = endpoint,
                reasoning = reasoningBuilder.takeIf { it.isNotEmpty() }?.toString(),
            )
            listener.onComplete(llmResponse)
        } catch (e: LlmException) {
            listener.onError(e)
        } catch (e: Exception) {
            listener.onError(LlmException(e.message ?: "Streaming error", e))
        }
    }

    // --- Embeddings ---

    override fun embed(request: EmbeddingRequest, endpoint: LlmEndpoint): EmbeddingResponse {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val modelId = endpoint.model ?: ""

        val requests = JsonArray(request.input.map { text ->
            JsonObject(
                mapOf(
                    "model" to "models/$modelId",
                    "content" to JsonObject(
                        mapOf(
                            "parts" to JsonArray(listOf(JsonObject(mapOf("text" to text)))),
                        )
                    ),
                )
            )
        })

        val body = JsonObject(mapOf("requests" to requests))
        val url = "$baseUrl/v1beta/models/$modelId:batchEmbedContents?key=$apiKey"

        val httpRequest = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val client = clientPool.getClient(endpoint)
        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Gemini Embedding API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("Gemini Embedding API error ${response.code}: $responseBody")
        }

        return parseEmbeddingResponse(responseBody, modelId)
    }

    internal fun parseEmbeddingResponse(body: String, modelId: String): EmbeddingResponse {
        val json = body.asRequiredJsonObject()
        val embeddings = json.reqArray("embeddings")
        val vectors = embeddings.items.map { entry ->
            val values = entry.reqObj.reqArray("values")
            FloatArray(values.size) { i -> values[i].double?.toFloat() ?: 0f }
        }
        return EmbeddingResponse(
            embeddings = vectors,
            model = modelId,
            inputTokens = null,
        )
    }

    // --- Request building ---

    internal fun buildRequestJson(request: LlmRequest): JsonObject {
        val json = JsonObject()

        val systemMessage = request.messages.firstOrNull { it.role == PromptRole.SYSTEM }
        val otherMessages = request.messages.filter { it.role != PromptRole.SYSTEM }

        if (systemMessage != null) {
            json["systemInstruction"] = JsonObject(
                mapOf(
                    "parts" to JsonArray(listOf(JsonObject(mapOf("text" to systemMessage.text)))),
                )
            )
        }

        json["contents"] = JsonArray(otherMessages.map { message ->
            val role = when (message.role) {
                PromptRole.ASSISTANT -> "model"
                PromptRole.TOOL -> "user"
                else -> message.role.wireValue
            }
            JsonObject(
                mapOf(
                    "role" to role,
                    "parts" to JsonArray(message.content.map { part -> contentPartToGeminiJson(part) }),
                )
            )
        })

        val generationConfig = JsonObject()

        if (request.temperature != null) {
            generationConfig["temperature"] = request.temperature
        }

        if (request.maxTokens != null) {
            generationConfig["maxOutputTokens"] = request.maxTokens
        }

        if (request.topP != null) {
            generationConfig["topP"] = request.topP
        }

        if (request.topK != null) {
            generationConfig["topK"] = request.topK
        }

        if (request.stopSequences != null) {
            generationConfig["stopSequences"] = JsonArray(request.stopSequences)
        }

        if (request.presencePenalty != null) {
            generationConfig["presencePenalty"] = request.presencePenalty
        }

        if (request.frequencyPenalty != null) {
            generationConfig["frequencyPenalty"] = request.frequencyPenalty
        }

        // A null effort means the provider default (no thinkingConfig); NONE disables thinking
        // with a zero budget and no thought output.
        if (request.reasoningEffort != null) {
            val thinkingConfig = JsonObject()
            if (request.reasoningEffort == LlmReasoningEffort.NONE) {
                thinkingConfig["thinkingBudget"] = 0
            } else {
                val budgetTokens = when (request.reasoningEffort) {
                    LlmReasoningEffort.LOW -> 1024
                    LlmReasoningEffort.MEDIUM -> 4096
                    LlmReasoningEffort.HIGH -> 16384
                    else -> null
                }
                if (budgetTokens != null) {
                    thinkingConfig["thinkingBudget"] = budgetTokens
                }
                thinkingConfig["includeThoughts"] = true
            }
            generationConfig["thinkingConfig"] = thinkingConfig
        }

        if (request.responseSchema != null) {
            generationConfig["responseMimeType"] = "application/json"
            generationConfig["responseSchema"] = request.responseSchema.toJsonObject()
        }

        json["generationConfig"] = generationConfig

        // Tools
        if (!request.tools.isNullOrEmpty() && request.responseSchema == null) {
            val functionDeclarations = JsonArray(request.tools.map { tool ->
                JsonObject(
                    mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "parameters" to tool.parameters.toJsonObject(),
                    )
                )
            })
            json["tools"] = JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "functionDeclarations" to functionDeclarations,
                        )
                    )
                )
            )
        }

        // Tool choice
        if (request.toolChoice != null && !request.tools.isNullOrEmpty() && request.responseSchema == null) {
            val functionCallingConfig = when (request.toolChoice) {
                is ToolChoice.Auto -> JsonObject(mapOf("mode" to "AUTO"))
                is ToolChoice.None -> JsonObject(mapOf("mode" to "NONE"))
                is ToolChoice.Required -> JsonObject(mapOf("mode" to "ANY"))
                is ToolChoice.Specific -> JsonObject(
                    mapOf(
                        "mode" to "ANY",
                        "allowedFunctionNames" to JsonArray(listOf(request.toolChoice.name)),
                    )
                )
            }
            json["toolConfig"] = JsonObject(mapOf("functionCallingConfig" to functionCallingConfig))
        }

        return request.applyExtraBodyParameters(json)
    }

    private fun contentPartToGeminiJson(part: LlmContentPart): JsonObject {
        return when (part) {
            is LlmContentPart.Text -> JsonObject(mapOf("text" to part.text))
            is LlmContentPart.Image -> when (val source = part.source) {
                is ImageSource.Base64 -> JsonObject(
                    mapOf(
                        "inlineData" to JsonObject(
                            mapOf(
                                "mimeType" to source.mediaType,
                                "data" to source.data,
                            )
                        ),
                    )
                )
                is ImageSource.Url -> JsonObject(
                    mapOf(
                        "fileData" to JsonObject(
                            mapOf(
                                "mimeType" to "image/*",
                                "fileUri" to source.url,
                            )
                        ),
                    )
                )
            }
            is LlmContentPart.Document -> when (val source = part.source) {
                is DocumentSource.Base64 -> JsonObject(
                    mapOf(
                        "inlineData" to JsonObject(
                            mapOf(
                                "mimeType" to source.mediaType,
                                "data" to source.data,
                            )
                        ),
                    )
                )
                is DocumentSource.Url -> JsonObject(
                    mapOf(
                        "fileData" to JsonObject(
                            mapOf(
                                "mimeType" to "application/pdf",
                                "fileUri" to source.url,
                            )
                        ),
                    )
                )
            }
            is LlmContentPart.Audio -> JsonObject(mapOf("text" to "[audio content]"))
            is LlmContentPart.ToolUse -> JsonObject(
                mapOf(
                    "functionCall" to JsonObject(
                        mapOf(
                            "id" to part.id,
                            "name" to part.name,
                            "args" to part.input,
                        )
                    ),
                )
            )
            is LlmContentPart.ToolResult -> {
                val functionResponse = JsonObject(
                    mapOf(
                        "name" to (part.toolName ?: part.toolUseId),
                        "response" to JsonObject(mapOf("content" to part.content)),
                    )
                )
                if (part.toolUseId.isNotBlank()) {
                    functionResponse["id"] = part.toolUseId
                }
                JsonObject(mapOf("functionResponse" to functionResponse))
            }
        }
    }

    // --- Response parsing ---

    internal fun parseResponse(body: String, modelId: String, endpoint: LlmEndpoint): LlmResponse {
        val json = body.asRequiredJsonObject()
        return parseGenerateContentJson(json, modelId, endpoint)
    }

    internal fun parseGenerateContentJson(json: JsonObject, modelId: String, endpoint: LlmEndpoint? = null): LlmResponse {
        val candidates = json.reqArray("candidates")
        val candidate = candidates[0].reqObj
        val content = candidate.reqObj("content")
        val parts = content.reqArray("parts")

        val contentParts = mutableListOf<LlmContentPart>()
        val thinkingParts = mutableListOf<String>()
        for (i in 0 until parts.size) {
            val part = parts[i].reqObj
            val thought = part.boolean("thought") ?: false
            val text = part.string("text")
            if (text != null) {
                if (thought) {
                    thinkingParts.add(text)
                } else {
                    contentParts.add(LlmContentPart.Text(text))
                }
            }
            val functionCall = part.obj("functionCall")
            if (functionCall != null) {
                val id = functionCall.string("id") ?: ""
                val name = functionCall.reqString("name")
                val args = functionCall.obj("args") ?: JsonObject()
                contentParts.add(LlmContentPart.ToolUse(id, name, args))
            }
        }

        val usageMetadata = json.obj("usageMetadata")
        val finishReason = candidate.string("finishReason")
        val reasoning = thinkingParts.ifEmpty { null }?.joinToString("\n")

        return LlmResponse(
            contentParts = contentParts,
            model = modelId,
            inputTokens = usageMetadata?.get("promptTokenCount")?.int,
            outputTokens = usageMetadata?.get("candidatesTokenCount")?.int,
            finishReason = mapFinishReason(finishReason),
            endpoint = endpoint,
            reasoning = reasoning,
        )
    }

    internal fun mapFinishReason(reason: String?): FinishReason? {
        if (reason == null) return null
        return when (reason) {
            "STOP" -> FinishReason.STOP
            "MAX_TOKENS" -> FinishReason.MAX_TOKENS
            "SAFETY" -> FinishReason.CONTENT_FILTER
            else -> null
        }
    }

    // --- Batch ---

    override fun submitBatch(items: List<MLBatchItem>, endpoint: LlmEndpoint): String {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val modelId = endpoint.model ?: ""
        val body = buildBatchRequestJson(items, modelId)

        val httpRequest = Request.Builder()
            .url(buildBatchCreateUrl(baseUrl, modelId, apiKey))
            .header("Content-Type", "application/json")
            .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val client = clientPool.getClient(endpoint)
        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Gemini Batch API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("Gemini Batch API error ${response.code}: $responseBody")
        }

        val json = responseBody.asRequiredJsonObject()
        return json.reqString("name")
    }

    override fun getBatchStatus(batchId: String, endpoint: LlmEndpoint): MLBatchStatus {
        val json = fetchBatchJson(batchId, endpoint)
        val status = extractBatchState(json)
        return parseBatchStatus(status)
    }

    override fun getBatchResults(batchId: String, endpoint: LlmEndpoint): List<MLBatchResult> {
        val json = fetchBatchJson(batchId, endpoint)
        val response = json.obj("response")
        val dest = json.obj("dest") ?: response?.obj("dest")
        val inlinedResponses = extractInlinedResponses(response)
            ?: extractInlinedResponses(dest)
        if (inlinedResponses != null && inlinedResponses.size > 0) {
            return parseInlinedResponses(inlinedResponses)
        }

        val fileName = response?.string("responsesFile")
            ?: response?.obj("dest")?.string("fileName")
            ?: response?.obj("dest")?.string("file_name")
            ?: dest?.string("responsesFile")
            ?: dest?.string("fileName")
            ?: dest?.string("file_name")
            ?: throw LlmException("Batch $batchId has no results")
        val content = downloadFile(fileName, endpoint)
        return parseBatchResultsJsonl(content)
    }

    internal fun buildBatchRequestJson(items: List<MLBatchItem>, modelId: String): JsonObject {
        val batchRequests = JsonArray(items.map { item ->
            val requestJson = buildRequestJson(item.request)
            requestJson["model"] = "models/$modelId"
            JsonObject(
                mapOf(
                    "request" to requestJson,
                    "metadata" to JsonObject(mapOf("key" to item.id)),
                )
            )
        })

        return JsonObject(
            mapOf(
                "batch" to JsonObject(
                    mapOf(
                        "input_config" to JsonObject(
                            mapOf(
                                "requests" to JsonObject(
                                    mapOf(
                                        "requests" to batchRequests,
                                    )
                                ),
                            )
                        ),
                    )
                ),
            )
        )
    }

    internal fun buildBatchCreateUrl(baseUrl: String, modelId: String, apiKey: String): String {
        return "$baseUrl/v1beta/models/$modelId:batchGenerateContent?key=$apiKey"
    }

    internal fun parseBatchStatus(status: String): MLBatchStatus {
        return when (status) {
            "JOB_STATE_PENDING", "JOB_STATE_RUNNING",
            "BATCH_STATE_PENDING", "BATCH_STATE_RUNNING" -> MLBatchStatus.IN_PROGRESS
            "JOB_STATE_SUCCEEDED", "BATCH_STATE_SUCCEEDED" -> MLBatchStatus.COMPLETED
            "JOB_STATE_FAILED", "BATCH_STATE_FAILED" -> MLBatchStatus.FAILED
            "JOB_STATE_CANCELLED", "BATCH_STATE_CANCELLED" -> MLBatchStatus.CANCELLED
            "JOB_STATE_EXPIRED", "BATCH_STATE_EXPIRED" -> MLBatchStatus.EXPIRED
            else -> MLBatchStatus.FAILED
        }
    }

    internal fun extractBatchState(json: JsonObject): String {
        return json.string("state")
            ?: json.obj("metadata")?.string("state")
            ?: json.obj("response")?.string("state")
            ?: throw IllegalStateException("Gemini batch status response did not contain a state field")
    }

    internal fun extractInlinedResponses(json: JsonObject?): JsonArray? {
        if (json == null) return null

        val direct = json.array("inlinedResponses")
            ?: json.array("inlined_responses")
        if (direct != null) return direct

        val wrapped = json.obj("inlinedResponses")
            ?: json.obj("inlined_responses")
        return wrapped?.array("inlinedResponses")
            ?: wrapped?.array("inlined_responses")
    }

    internal fun parseInlinedResponses(responses: JsonArray): List<MLBatchResult> {
        return responses.items.map { entry ->
            val entryJson = entry.reqObj
            val key = extractBatchResultKey(entryJson)
            val response = entryJson.obj("response")

            if (response != null) {
                try {
                    val modelId = response.string("modelVersion") ?: ""
                    MLBatchResult(
                        id = key,
                        response = parseGenerateContentJson(response, modelId),
                        error = null,
                    )
                } catch (e: Exception) {
                    MLBatchResult(key, null, e.message ?: "Failed to parse response")
                }
            } else {
                val error = entryJson.obj("error")
                MLBatchResult(key, null, error?.string("message") ?: "Unknown error")
            }
        }
    }

    internal fun parseBatchResultsJsonl(jsonl: String): List<MLBatchResult> {
        return jsonl.lines().filter { it.isNotBlank() }.map { line ->
            val json = line.asRequiredJsonObject()
            val key = extractBatchResultKey(json)
            val response = json.obj("response")

            if (response != null) {
                try {
                    val modelId = response.string("modelVersion") ?: ""
                    MLBatchResult(
                        id = key,
                        response = parseGenerateContentJson(response, modelId),
                        error = null,
                    )
                } catch (e: Exception) {
                    MLBatchResult(key, null, e.message ?: "Failed to parse response")
                }
            } else {
                val error = json.obj("error")
                MLBatchResult(key, null, error?.string("message") ?: "Unknown error")
            }
        }
    }

    internal fun extractBatchResultKey(json: JsonObject): String {
        return json.string("key")
            ?: json.obj("metadata")?.string("key")
            ?: ""
    }

    private fun fetchBatchJson(batchId: String, endpoint: LlmEndpoint): JsonObject {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1beta/$batchId?key=$apiKey")
            .header("Content-Type", "application/json")
            .get()
            .build()

        val client = clientPool.getClient(endpoint)
        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Gemini Batch API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("Gemini Batch API error ${response.code}: $responseBody")
        }

        return responseBody.asRequiredJsonObject()
    }

    private fun downloadFile(fileName: String, endpoint: LlmEndpoint): String {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")

        val httpRequest = Request.Builder()
            .url("$baseUrl/download/v1beta/$fileName:download?alt=media&key=$apiKey")
            .get()
            .build()

        val client = clientPool.getClient(endpoint)
        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Gemini Files API")

        if (!response.isSuccessful) {
            throw LlmException("Gemini Files API error ${response.code}: $responseBody")
        }

        return responseBody
    }

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}
