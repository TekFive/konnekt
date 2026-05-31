package org.tekfive.konnekt.llm.providers.cohere

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.konnekt.llm.ChatProvider
import org.tekfive.konnekt.llm.LlmContentPart
import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.FinishReason
import org.tekfive.konnekt.llm.content.ImageSource
import org.tekfive.konnekt.llm.LlmException
import org.tekfive.konnekt.llm.LlmRequest
import org.tekfive.konnekt.llm.LlmResponse
import org.tekfive.konnekt.llm.PromptRole
import org.tekfive.konnekt.llm.embedding.EmbeddingProvider
import org.tekfive.konnekt.llm.embedding.EmbeddingRequest
import org.tekfive.konnekt.llm.embedding.EmbeddingResponse
import org.tekfive.konnekt.llm.stream.StreamListener
import org.tekfive.konnekt.llm.stream.StreamingProvider
import org.tekfive.konnekt.llm.content.ToolChoice
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * ML service provider for the Cohere v2 Chat API.
 *
 * Supports chat completions, streaming, tool calling, and embeddings.
 * Cohere v2 uses `p` for top_p and `k` for top_k.
 * Structured output uses `response_format` with `type: "json_object"` and `json_schema`.
 */
object CohereServiceProvider : ChatProvider, StreamingProvider, EmbeddingProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    // --- Chat ---

    override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val body = buildRequestJson(request, endpoint)

        val httpRequest = Request.Builder()
            .url("$baseUrl/v2/chat")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Cohere API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("Cohere API error ${response.code}: $responseBody")
        }

        return parseResponse(responseBody, endpoint)
    }

    // --- Streaming ---

    override fun chatStream(request: LlmRequest, endpoint: LlmEndpoint, listener: StreamListener) {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")

        try {
            val body = buildRequestJson(request, endpoint)
            body["stream"] = true

            val httpRequest = Request.Builder()
                .url("$baseUrl/v2/chat")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(httpRequest).execute()

            if (response.code == 429) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw LlmException(
                    "${type.displayName} rate limited: $errorBody",
                    isRateLimited = true,
                )
            }

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                listener.onError(LlmException("Cohere API error ${response.code}: $errorBody"))
                return
            }

            val inputStream = response.body?.byteStream()
                ?: run {
                    listener.onError(LlmException("Empty response from Cohere API"))
                    return
                }

            val reader = BufferedReader(InputStreamReader(inputStream))
            val contentBuilder = StringBuilder()
            var model = endpoint.model ?: ""
            var finishReason: FinishReason? = null

            reader.use {
                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data.isEmpty()) {
                            line = reader.readLine()
                            continue
                        }
                        val chunk = data.asRequiredJsonObject()
                        val type = chunk.string("type")

                        when (type) {
                            "content-delta" -> {
                                val delta = chunk.obj("delta")
                                val message = delta?.obj("message")
                                val content = message?.obj("content")
                                val text = content?.string("text")
                                if (text != null) {
                                    contentBuilder.append(text)
                                    listener.onToken(text)
                                }
                            }
                            "message-end" -> {
                                val delta = chunk.obj("delta")
                                val reason = delta?.string("finish_reason")
                                if (reason != null) {
                                    finishReason = mapFinishReason(reason)
                                }
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }

            val llmResponse = LlmResponse(
                contentParts = listOf(LlmContentPart.Text(contentBuilder.toString())),
                model = model,
                finishReason = finishReason,
                endpoint = endpoint,
            )
            listener.onComplete(llmResponse)
        } catch (e: LlmException) {
            listener.onError(e)
        } catch (e: Exception) {
            listener.onError(LlmException("Streaming error: ${e.message}", e))
        }
    }

    // --- Embeddings ---

    override fun embed(request: EmbeddingRequest, endpoint: LlmEndpoint): EmbeddingResponse {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")

        val body = JsonObject(mapOf(
            "model" to (endpoint.model ?: ""),
            "texts" to JsonArray(request.input),
            "input_type" to "search_document",
            "embedding_types" to JsonArray(listOf("float")),
        ))

        val httpRequest = Request.Builder()
            .url("$baseUrl/v2/embed")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Cohere Embedding API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("Cohere Embedding API error ${response.code}: $responseBody")
        }

        return parseEmbeddingResponse(responseBody, endpoint.model ?: "")
    }

    // --- Request building ---

    internal fun buildRequestJson(request: LlmRequest, endpoint: LlmEndpoint): JsonObject {
        val json = JsonObject()
        endpoint.model?.let { json["model"] = it }

        json["messages"] = JsonArray(request.messages.flatMap { message -> serializeMessage(message) })

        request.temperature?.let { json["temperature"] = it }
        request.maxTokens?.let { json["max_tokens"] = it }
        request.topP?.let { json["p"] = it }
        request.topK?.let { json["k"] = it }
        request.stopSequences?.let { json["stop_sequences"] = JsonArray(it) }
        request.presencePenalty?.let { json["presence_penalty"] = it }
        request.frequencyPenalty?.let { json["frequency_penalty"] = it }

        if (request.responseSchema != null) {
            json["response_format"] = JsonObject(mapOf(
                "type" to "json_object",
                "json_schema" to request.responseSchema.toJsonObject(),
            ))
        }

        if (request.tools != null && request.responseSchema == null) {
            json["tools"] = JsonArray(request.tools.map { tool ->
                JsonObject(mapOf(
                    "type" to "function",
                    "function" to JsonObject(mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "parameters" to tool.parameters.toJsonObject(),
                    )),
                ))
            })
        }

        val toolChoice = request.toolChoice
        if (toolChoice != null && request.responseSchema == null) {
            json["tool_choice"] = when (toolChoice) {
                is ToolChoice.Auto -> "auto"
                is ToolChoice.None -> "none"
                is ToolChoice.Required -> "required"
                is ToolChoice.Specific -> JsonObject(mapOf(
                    "type" to "function",
                    "function" to JsonObject(mapOf("name" to toolChoice.name)),
                ))
            }
        }

        return request.applyExtraBodyParameters(json)
    }

    // --- Message serialization ---

    private fun serializeMessage(message: org.tekfive.konnekt.llm.LlmMessage): List<JsonObject> {
        if (message.role == PromptRole.TOOL) {
            return message.content.filterIsInstance<LlmContentPart.ToolResult>().map { part ->
                JsonObject(mapOf(
                    "role" to "tool",
                    "tool_call_id" to part.toolUseId,
                    "content" to part.content,
                ))
            }
        }

        val msgJson = JsonObject()
        msgJson["role"] = message.role.wireValue

        val toolUseParts = message.content.filterIsInstance<LlmContentPart.ToolUse>()
        val nonToolUseParts = message.content.filter { it !is LlmContentPart.ToolUse }

        if (nonToolUseParts.size == 1 && nonToolUseParts[0] is LlmContentPart.Text) {
            msgJson["content"] = (nonToolUseParts[0] as LlmContentPart.Text).text
        } else if (nonToolUseParts.isNotEmpty()) {
            msgJson["content"] = JsonArray(nonToolUseParts.mapNotNull { part ->
                when (part) {
                    is LlmContentPart.Text -> JsonObject(mapOf("type" to "text", "text" to part.text))
                    is LlmContentPart.Image -> serializeImagePart(part)
                    else -> null
                }
            })
        }

        if (toolUseParts.isNotEmpty()) {
            msgJson["tool_calls"] = JsonArray(toolUseParts.map { toolUse ->
                JsonObject(mapOf(
                    "id" to toolUse.id,
                    "type" to "function",
                    "function" to JsonObject(mapOf(
                        "name" to toolUse.name,
                        "arguments" to toolUse.input.toJsonString(),
                    )),
                ))
            })
        }

        return listOf(msgJson)
    }

    private fun serializeImagePart(part: LlmContentPart.Image): JsonObject {
        val url = when (val source = part.source) {
            is ImageSource.Base64 -> "data:${source.mediaType};base64,${source.data}"
            is ImageSource.Url -> source.url
        }
        return JsonObject(mapOf(
            "type" to "image_url",
            "image_url" to JsonObject(mapOf("url" to url)),
        ))
    }

    // --- Response parsing ---

    internal fun parseResponse(body: String, endpoint: LlmEndpoint): LlmResponse {
        val json = body.asRequiredJsonObject()
        return parseResponseJson(json, endpoint)
    }

    internal fun parseResponseJson(json: JsonObject, endpoint: LlmEndpoint? = null): LlmResponse {
        val message = json.reqObj("message")
        val contentParts = mutableListOf<LlmContentPart>()

        // Content can be an array of objects with type/text
        val contentArray = message.array("content")
        if (contentArray != null) {
            for (i in 0 until contentArray.size) {
                val item = contentArray[i].reqObj
                val text = item.string("text")
                if (text != null) {
                    contentParts.add(LlmContentPart.Text(text))
                }
            }
        }

        // Tool calls
        message.array("tool_calls")?.let { toolCalls ->
            for (element in toolCalls) {
                val toolCall = element.reqObj
                val function = toolCall.reqObj("function")
                contentParts.add(LlmContentPart.ToolUse(
                    id = toolCall.reqString("id"),
                    name = function.reqString("name"),
                    input = function.reqString("arguments").asRequiredJsonObject(),
                ))
            }
        }

        val usage = json.obj("usage")
        val tokens = usage?.obj("tokens")
        val billedUnits = usage?.obj("billed_units")
        val finishReasonStr = json.string("finish_reason")
        val model = json.string("model") ?: endpoint?.model ?: ""

        return LlmResponse(
            contentParts = contentParts,
            model = model,
            inputTokens = tokens?.get("input_tokens")?.int ?: billedUnits?.get("input_tokens")?.int,
            outputTokens = tokens?.get("output_tokens")?.int ?: billedUnits?.get("output_tokens")?.int,
            finishReason = finishReasonStr?.let { mapFinishReason(it) },
            endpoint = endpoint,
        )
    }

    // --- Embedding response parsing ---

    internal fun parseEmbeddingResponse(body: String, model: String): EmbeddingResponse {
        val json = body.asRequiredJsonObject()
        val embeddings = json.reqObj("embeddings")
        val floatArrays = embeddings.reqArray("float")

        val vectors = floatArrays.items.map { entry ->
            val values = entry.array!!
            FloatArray(values.size) { i -> values[i].double?.toFloat() ?: 0f }
        }

        val meta = json.obj("meta")
        val billedUnits = meta?.obj("billed_units")

        return EmbeddingResponse(
            embeddings = vectors,
            model = model,
            inputTokens = billedUnits?.get("input_tokens")?.int,
        )
    }

    // --- Finish reason mapping ---

    internal fun mapFinishReason(reason: String): FinishReason {
        return when (reason) {
            "COMPLETE" -> FinishReason.STOP
            "MAX_TOKENS" -> FinishReason.MAX_TOKENS
            "TOOL_CALL" -> FinishReason.TOOL_USE
            else -> FinishReason.STOP
        }
    }
}
