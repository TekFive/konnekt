package org.tekfive.konnekt.llm.providers.antrophic

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.konnekt.llm.batch.BatchProvider
import org.tekfive.konnekt.llm.ChatProvider
import org.tekfive.konnekt.llm.LlmContentPart
import org.tekfive.konnekt.llm.content.DocumentSource
import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.FinishReason
import org.tekfive.konnekt.llm.content.ImageSource
import org.tekfive.konnekt.llm.LlmException
import org.tekfive.konnekt.llm.LlmRequest
import org.tekfive.konnekt.llm.LlmResponse
import org.tekfive.konnekt.llm.LlmReasoningEffort
import org.tekfive.konnekt.llm.RateLimits
import org.tekfive.konnekt.llm.PromptRole
import org.tekfive.konnekt.llm.batch.MLBatchItem
import org.tekfive.konnekt.llm.batch.MLBatchResult
import org.tekfive.konnekt.llm.batch.MLBatchStatus
import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.stream.StreamListener
import org.tekfive.konnekt.llm.stream.StreamingProvider
import org.tekfive.konnekt.llm.content.ToolChoice
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * ML service provider for the Anthropic Messages API.
 *
 * System messages are sent in the top-level `system` field.
 * Structured output uses the tool-use pattern with `tool_choice`.
 * Batch support uses the Message Batches API (`/v1/messages/batches`).
 */
object AnthropicServiceProvider : ChatProvider, StreamingProvider, BatchProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val DEFAULT_API_VERSION = "2023-06-01"

    // --- Chat ---

    override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val body = buildRequestJson(request, endpoint)

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", DEFAULT_API_VERSION)
            .header("content-type", "application/json")
            .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Anthropic API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("Anthropic API error ${response.code}: $responseBody")
        }

        return parseResponse(responseBody, response, endpoint)
    }

    // --- Streaming ---

    override fun chatStream(request: LlmRequest, endpoint: LlmEndpoint, listener: StreamListener) {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val body = buildRequestJson(request, endpoint)
        body["stream"] = true

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", DEFAULT_API_VERSION)
            .header("content-type", "application/json")
            .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body
                ?: throw LlmException("Empty response from Anthropic API")

            if (response.code == 429) {
                val errorBody = responseBody.string()
                throw LlmException(
                    "${type.displayName} rate limited: $errorBody",
                    isRateLimited = true,
                )
            }

            if (!response.isSuccessful) {
                val errorBody = responseBody.string()
                throw LlmException("Anthropic API error ${response.code}: $errorBody")
            }

            val rateLimits = parseRateLimits(response)

            var model = ""
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            var finishReason: FinishReason? = null
            val contentParts = mutableListOf<LlmContentPart>()
            val textBuffer = StringBuilder()
            val reasoningBuffer = StringBuilder()

            val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))
            var currentEvent = ""

            reader.use {
                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("event: ")) {
                        currentEvent = line.removePrefix("event: ").trim()
                    } else if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data.isNotEmpty()) {
                            val json = data.asRequiredJsonObject()
                            when (currentEvent) {
                                "message_start" -> {
                                    val message = json.reqObj("message")
                                    model = message.reqString("model")
                                    val usage = message.obj("usage")
                                    inputTokens = usage?.get("input_tokens")?.int
                                }
                                "content_block_delta" -> {
                                    val delta = json.reqObj("delta")
                                    when (delta.reqString("type")) {
                                        "thinking_delta" -> {
                                            val thinking = delta.reqString("thinking")
                                            reasoningBuffer.append(thinking)
                                            listener.onReasoningToken(thinking)
                                        }
                                        "text_delta" -> {
                                            val text = delta.reqString("text")
                                            textBuffer.append(text)
                                            listener.onToken(text)
                                        }
                                    }
                                }
                                "message_delta" -> {
                                    val delta = json.reqObj("delta")
                                    val stopReason = delta.string("stop_reason")
                                    if (stopReason != null) {
                                        finishReason = mapStopReason(stopReason)
                                    }
                                    val usage = json.obj("usage")
                                    if (usage != null) {
                                        outputTokens = usage.get("output_tokens")?.int
                                    }
                                }
                                "message_stop" -> {
                                    if (textBuffer.isNotEmpty()) {
                                        contentParts.add(LlmContentPart.Text(textBuffer.toString()))
                                    }
                                    val llmResponse = LlmResponse(
                                        contentParts = contentParts.ifEmpty { listOf(LlmContentPart.Text("")) },
                                        model = model,
                                        inputTokens = inputTokens,
                                        outputTokens = outputTokens,
                                        finishReason = finishReason,
                                        endpoint = endpoint,
                                        rateLimits = rateLimits,
                                        reasoning = reasoningBuffer.takeIf { it.isNotEmpty() }?.toString(),
                                    )
                                    listener.onComplete(llmResponse)
                                }
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: LlmException) {
            listener.onError(e)
        } catch (e: Exception) {
            listener.onError(LlmException("Streaming error: ${e.message}", e))
        }
    }

    internal fun buildRequestJson(request: LlmRequest, endpoint: LlmEndpoint): JsonObject {
        val json = JsonObject()
        endpoint.model?.let { json["model"] = it }

        val systemMessage = request.messages.firstOrNull { it.role == PromptRole.SYSTEM }
        val otherMessages = request.messages.filter { it.role != PromptRole.SYSTEM }

        if (systemMessage != null) {
            json["system"] = systemMessage.text
        }

        json["messages"] = JsonArray(otherMessages.map { msg ->
            val msgJson = JsonObject()
            // TOOL role maps to "user" with tool_result content blocks
            msgJson["role"] = if (msg.role == PromptRole.TOOL) "user" else msg.role.wireValue
            msgJson["content"] = serializeContent(msg.content)
            msgJson
        })
        json["temperature"] = request.temperature ?: 0.7
        json["max_tokens"] = request.maxTokens ?: 4096

        if (request.topP != null) {
            json["top_p"] = request.topP
        }
        if (request.topK != null) {
            json["top_k"] = request.topK
        }
        if (request.stopSequences != null) {
            json["stop_sequences"] = JsonArray(request.stopSequences)
        }

        // A null effort means the provider default (no thinking field); NONE disables explicitly.
        if (request.reasoningEffort != null) {
            json["thinking"] = when (request.reasoningEffort) {
                LlmReasoningEffort.NONE -> JsonObject(mapOf("type" to "disabled"))
                LlmReasoningEffort.LOW -> JsonObject(mapOf("type" to "enabled", "budget_tokens" to 1024))
                LlmReasoningEffort.MEDIUM -> JsonObject(mapOf("type" to "enabled", "budget_tokens" to 4096))
                LlmReasoningEffort.HIGH -> JsonObject(mapOf("type" to "enabled", "budget_tokens" to 16384))
            }
        }

        if (request.responseSchema != null) {
            val schemaName = request.responseSchema.title ?: "response"
            val tool = JsonObject(
                mapOf(
                    "name" to schemaName,
                    "description" to (request.responseSchema.description ?: ""),
                    "input_schema" to request.responseSchema.toJsonObject(),
                )
            )
            json["tools"] = JsonArray(listOf(tool))
            json["tool_choice"] = JsonObject(mapOf("type" to "tool", "name" to schemaName))
        } else if (!request.tools.isNullOrEmpty()) {
            json["tools"] = JsonArray(request.tools.map { tool ->
                JsonObject(
                    mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "input_schema" to tool.parameters.toJsonObject(),
                    )
                )
            })
            if (request.toolChoice != null) {
                when (request.toolChoice) {
                    is ToolChoice.Auto -> json["tool_choice"] = JsonObject(mapOf("type" to "auto"))
                    is ToolChoice.None -> { /* omit tool_choice */ }
                    is ToolChoice.Required -> json["tool_choice"] = JsonObject(mapOf("type" to "any"))
                    is ToolChoice.Specific -> json["tool_choice"] = JsonObject(
                        mapOf(
                            "type" to "tool",
                            "name" to request.toolChoice.name,
                        )
                    )
                }
            }
        }

        return request.applyExtraBodyParameters(json)
    }

    private fun serializeContent(parts: List<LlmContentPart>): Any {
        // If single text part, use string shorthand
        if (parts.size == 1 && parts[0] is LlmContentPart.Text) {
            return (parts[0] as LlmContentPart.Text).text
        }
        // Otherwise, use array of content blocks
        return JsonArray(parts.map { part -> serializeContentPart(part) })
    }

    private fun serializeContentPart(part: LlmContentPart): JsonObject {
        return when (part) {
            is LlmContentPart.Text -> JsonObject(
                mapOf(
                    "type" to "text",
                    "text" to part.text,
                )
            )
            is LlmContentPart.Image -> when (val source = part.source) {
                is ImageSource.Base64 -> JsonObject(
                    mapOf(
                        "type" to "image",
                        "source" to JsonObject(
                            mapOf(
                                "type" to "base64",
                                "media_type" to source.mediaType,
                                "data" to source.data,
                            )
                        ),
                    )
                )
                is ImageSource.Url -> JsonObject(
                    mapOf(
                        "type" to "image",
                        "source" to JsonObject(
                            mapOf(
                                "type" to "url",
                                "url" to source.url,
                            )
                        ),
                    )
                )
            }
            is LlmContentPart.Document -> when (val source = part.source) {
                is DocumentSource.Base64 -> JsonObject(
                    mapOf(
                        "type" to "document",
                        "source" to JsonObject(
                            mapOf(
                                "type" to "base64",
                                "media_type" to source.mediaType,
                                "data" to source.data,
                            )
                        ),
                    )
                )
                is DocumentSource.Url -> JsonObject(
                    mapOf(
                        "type" to "document",
                        "source" to JsonObject(
                            mapOf(
                                "type" to "url",
                                "url" to source.url,
                            )
                        ),
                    )
                )
            }
            is LlmContentPart.Audio -> throw LlmException("Anthropic does not support audio content")
            is LlmContentPart.ToolUse -> JsonObject(
                mapOf(
                    "type" to "tool_use",
                    "id" to part.id,
                    "name" to part.name,
                    "input" to part.input,
                )
            )
            is LlmContentPart.ToolResult -> JsonObject(
                mapOf(
                    "type" to "tool_result",
                    "tool_use_id" to part.toolUseId,
                    "content" to part.content,
                )
            )
        }
    }

    private fun parseRateLimits(response: Response): RateLimits {
        return RateLimits(
            remainingRequests = response.header("anthropic-ratelimit-requests-remaining")?.toIntOrNull(),
            remainingTokens = response.header("anthropic-ratelimit-tokens-remaining")?.toIntOrNull(),
            resetRequests = response.header("anthropic-ratelimit-requests-reset"),
            resetTokens = response.header("anthropic-ratelimit-tokens-reset"),
        )
    }

    internal fun parseResponse(body: String, response: Response, endpoint: LlmEndpoint): LlmResponse {
        val json = body.asRequiredJsonObject()
        return parseMessageJson(json, response, endpoint)
    }

    internal fun parseMessageJson(json: JsonObject, response: Response, endpoint: LlmEndpoint): LlmResponse {
        val content = json.reqArray("content")
        val contentParts = mutableListOf<LlmContentPart>()
        val thinkingParts = mutableListOf<String>()

        for (element in content) {
            val blockJson = element.reqObj
            when (blockJson.reqString("type")) {
                "thinking" -> blockJson.string("thinking")?.let { thinkingParts.add(it) }
                "text" -> contentParts.add(LlmContentPart.Text(blockJson.reqString("text")))
                "tool_use" -> contentParts.add(
                    LlmContentPart.ToolUse(
                    id = blockJson.reqString("id"),
                    name = blockJson.reqString("name"),
                    input = blockJson.reqObj("input"),
                ))
            }
        }

        val usage = json.obj("usage")
        val stopReason = json.string("stop_reason")
        val rateLimits = parseRateLimits(response)
        val reasoning = thinkingParts.ifEmpty { null }?.joinToString("\n")

        return LlmResponse(
            contentParts = contentParts,
            model = json.reqString("model"),
            inputTokens = usage?.get("input_tokens")?.int,
            outputTokens = usage?.get("output_tokens")?.int,
            finishReason = stopReason?.let { mapStopReason(it) },
            endpoint = endpoint,
            rateLimits = rateLimits,
            reasoning = reasoning,
        )
    }

    private fun mapStopReason(stopReason: String): FinishReason {
        return when (stopReason) {
            "end_turn" -> FinishReason.STOP
            "max_tokens" -> FinishReason.MAX_TOKENS
            "tool_use" -> FinishReason.TOOL_USE
            else -> FinishReason.STOP
        }
    }

    // --- Batch ---

    override fun submitBatch(items: List<MLBatchItem>, endpoint: LlmEndpoint): String {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val body = buildBatchRequestJson(items, endpoint)

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages/batches")
            .header("x-api-key", apiKey)
            .header("anthropic-version", DEFAULT_API_VERSION)
            .header("content-type", "application/json")
            .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Anthropic Batch API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("Anthropic Batch API error ${response.code}: $responseBody")
        }

        val json = responseBody.asRequiredJsonObject()
        return json.reqString("id")
    }

    override fun getBatchStatus(batchId: String, endpoint: LlmEndpoint): MLBatchStatus {
        val json = fetchBatchJson(batchId, endpoint)
        return parseBatchStatus(json.reqString("processing_status"))
    }

    override fun getBatchResults(batchId: String, endpoint: LlmEndpoint): List<MLBatchResult> {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages/batches/$batchId/results")
            .header("x-api-key", apiKey)
            .header("anthropic-version", DEFAULT_API_VERSION)
            .get()
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Anthropic Batch Results API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("Anthropic Batch Results API error ${response.code}: $responseBody")
        }

        return parseBatchResultsJsonl(responseBody)
    }

    internal fun buildBatchRequestJson(items: List<MLBatchItem>, endpoint: LlmEndpoint): JsonObject {
        val requests = JsonArray(items.map { item ->
            JsonObject(
                mapOf(
                    "custom_id" to item.id,
                    "params" to buildRequestJson(item.request, endpoint),
                )
            )
        })
        return JsonObject(mapOf("requests" to requests))
    }

    internal fun parseBatchStatus(status: String): MLBatchStatus {
        return when (status) {
            "in_progress" -> MLBatchStatus.IN_PROGRESS
            "ended" -> MLBatchStatus.COMPLETED
            "canceling" -> MLBatchStatus.IN_PROGRESS
            "canceled" -> MLBatchStatus.CANCELLED
            "expired" -> MLBatchStatus.EXPIRED
            else -> MLBatchStatus.FAILED
        }
    }

    internal fun parseBatchResultsJsonl(jsonl: String): List<MLBatchResult> {
        return jsonl.lines().filter { it.isNotBlank() }.map { line ->
            val json = line.asRequiredJsonObject()
            val customId = json.reqString("custom_id")
            val result = json.reqObj("result")
            val type = result.reqString("type")

            if (type == "succeeded") {
                val message = result.reqObj("message")
                MLBatchResult(
                    id = customId,
                    response = parseBatchMessageJson(message),
                    error = null,
                )
            } else {
                val error = result.obj("error")
                MLBatchResult(
                    id = customId,
                    response = null,
                    error = error?.string("message") ?: "Unknown error",
                )
            }
        }
    }

    /** Parse a message JSON from batch results (no HTTP response headers available). */
    private fun parseBatchMessageJson(json: JsonObject): LlmResponse {
        val content = json.reqArray("content")
        val contentParts = mutableListOf<LlmContentPart>()
        val thinkingParts = mutableListOf<String>()

        for (element in content) {
            val blockJson = element.reqObj
            when (blockJson.reqString("type")) {
                "thinking" -> blockJson.string("thinking")?.let { thinkingParts.add(it) }
                "text" -> contentParts.add(LlmContentPart.Text(blockJson.reqString("text")))
                "tool_use" -> contentParts.add(
                    LlmContentPart.ToolUse(
                    id = blockJson.reqString("id"),
                    name = blockJson.reqString("name"),
                    input = blockJson.reqObj("input"),
                ))
            }
        }

        val usage = json.obj("usage")
        val stopReason = json.string("stop_reason")
        val reasoning = thinkingParts.ifEmpty { null }?.joinToString("\n")

        return LlmResponse(
            contentParts = contentParts,
            model = json.reqString("model"),
            inputTokens = usage?.get("input_tokens")?.int,
            outputTokens = usage?.get("output_tokens")?.int,
            finishReason = stopReason?.let { mapStopReason(it) },
            reasoning = reasoning,
        )
    }

    private fun fetchBatchJson(batchId: String, endpoint: LlmEndpoint): JsonObject {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages/batches/$batchId")
            .header("x-api-key", apiKey)
            .header("anthropic-version", DEFAULT_API_VERSION)
            .get()
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Anthropic Batch API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("Anthropic Batch API error ${response.code}: $responseBody")
        }

        return responseBody.asRequiredJsonObject()
    }

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}
