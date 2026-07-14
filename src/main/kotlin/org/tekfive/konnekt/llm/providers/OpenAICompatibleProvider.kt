package org.tekfive.konnekt.llm.providers

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.konnekt.llm.ChatProvider
import org.tekfive.konnekt.llm.LlmContentPart
import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.FinishReason
import org.tekfive.konnekt.llm.LlmException
import org.tekfive.konnekt.llm.LlmRequest
import org.tekfive.konnekt.llm.LlmResponse
import org.tekfive.konnekt.llm.LlmMessage
import org.tekfive.konnekt.llm.RateLimits
import org.tekfive.konnekt.llm.PromptRole
import org.tekfive.konnekt.llm.content.ImageSource
import org.tekfive.konnekt.llm.content.ToolChoice
import org.tekfive.konnekt.llm.stream.StreamListener
import org.tekfive.konnekt.llm.stream.StreamingProvider
import org.tekfive.konnekt.llm.utils.OkHttpClientPool
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shared implementation for providers that use the OpenAI-compatible chat completions API.
 *
 * Subclasses customize credential validation, auth headers, rate-limit parsing,
 * model extraction, and additional request parameters via protected open methods.
 */
abstract class OpenAICompatibleProvider : ChatProvider, StreamingProvider {
    // --- Hooks for subclass customization ---

    internal val clientPool = OkHttpClientPool()

    protected open fun validateEndpoint(endpoint: LlmEndpoint) {
        if (endpoint.apiKey == null) throw LlmException("API key required for ${type.displayName}")
        if (endpoint.resolvedBaseUrl == null) throw LlmException("Base URL required for ${type.displayName}")
    }

    protected open fun addAuthHeaders(builder: Request.Builder, endpoint: LlmEndpoint) {
        endpoint.apiKey?.let { builder.header("Authorization", "Bearer $it") }
    }

    protected open fun extractModel(json: JsonObject, endpoint: LlmEndpoint): String {
        return json.reqString("model")
    }

    protected open fun parseRateLimits(response: Response): RateLimits? {
        return RateLimits(
            remainingRequests = response.header("x-ratelimit-remaining-requests")?.toIntOrNull(),
            remainingTokens = response.header("x-ratelimit-remaining-tokens")?.toIntOrNull(),
            resetRequests = response.header("x-ratelimit-reset-requests"),
            resetTokens = response.header("x-ratelimit-reset-tokens"),
        )
    }

    protected open fun addProviderSpecificParams(json: JsonObject, request: LlmRequest, endpoint: LlmEndpoint) {
        // Override to add provider-specific request parameters (e.g., top_k for VLLM)
    }

    /**
     * Adds the reasoning fields for the request's [LlmRequest.reasoningEffort]. A null effort means
     * the provider default and sends nothing; [LlmReasoningEffort.NONE] maps to `reasoning_effort:
     * "none"` by default. Override for providers that disable reasoning differently (e.g. VLLM's
     * `chat_template_kwargs`).
     */
    protected open fun addReasoningParams(json: JsonObject, request: LlmRequest) {
        request.reasoningEffort?.let { json["reasoning_effort"] = it.name.lowercase() }
    }

    protected open fun chatCompletionsUrl(endpoint: LlmEndpoint): String {
        val resolvedBaseUrl = endpoint.resolvedBaseUrl!!
        return if (resolvedBaseUrl.endsWith("chat/completions")) {
            resolvedBaseUrl
         } else {
            "$resolvedBaseUrl/v1/chat/completions"
        }
    }

    // --- Chat ---

    override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse {
        validateEndpoint(endpoint)
        val body = buildRequestJson(request, endpoint)

        val httpRequestBuilder = Request.Builder()
            .url(chatCompletionsUrl(endpoint))
            .header("Content-Type", "application/json")
            .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
        addAuthHeaders(httpRequestBuilder, endpoint)

        val client = clientPool.getClient(endpoint)
        val response = client.newCall(httpRequestBuilder.build()).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from ${type.displayName} API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("${type.displayName} API error ${response.code}: $responseBody")
        }

        return parseCompletionResponse(responseBody, response, endpoint)
    }

    // --- Streaming ---

    override fun chatStream(request: LlmRequest, endpoint: LlmEndpoint, listener: StreamListener) {
        validateEndpoint(endpoint)

        try {
            val body = buildRequestJson(request, endpoint)
            body["stream"] = true

            val httpRequestBuilder = Request.Builder()
                .url(chatCompletionsUrl(endpoint))
                .header("Content-Type", "application/json")
                .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            addAuthHeaders(httpRequestBuilder, endpoint)

            val client = clientPool.getClient(endpoint)
            val response = client.newCall(httpRequestBuilder.build()).execute()

            if (response.code == 429) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw LlmException(
                    "${type.displayName} rate limited: $errorBody",
                    isRateLimited = true,
                )
            }

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                listener.onError(LlmException("${type.displayName} API error ${response.code}: $errorBody"))
                return
            }

            val rateLimits = parseRateLimits(response)

            val inputStream = response.body?.byteStream()
                ?: run {
                    listener.onError(LlmException("Empty response from ${type.displayName} API"))
                    return
                }

            val reader = BufferedReader(InputStreamReader(inputStream))
            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            var model = ""
            var finishReason: FinishReason? = null

            reader.use {
                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") {
                            line = reader.readLine()
                            continue
                        }
                        val chunk = data.asRequiredJsonObject()
                        if (model.isEmpty()) {
                            model = chunk.string("model") ?: ""
                        }
                        val choices = chunk.array("choices")
                        if (choices != null && choices.size > 0) {
                            val choice = choices[0].reqObj
                            val delta = choice.obj("delta")
                            val content = delta?.string("content")
                            if (content != null) {
                                contentBuilder.append(content)
                                listener.onToken(content)
                            }

                            val reasoning = delta?.let { extractReasoningText(it) }
                            if (reasoning != null) {
                                reasoningBuilder.append(reasoning)
                                listener.onReasoningToken(reasoning)
                            }

                            val reason = choice.string("finish_reason")
                            if (reason != null) {
                                finishReason = mapFinishReason(reason)
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
                rateLimits = rateLimits,
                reasoning = reasoningBuilder.takeIf { it.isNotEmpty() }?.toString(),
            )
            listener.onComplete(llmResponse)
        } catch (e: LlmException) {
            listener.onError(e)
        } catch (e: Exception) {
            listener.onError(LlmException("Streaming error: ${e.message}", e))
        }
    }

    // --- Request building ---

    internal fun buildRequestJson(request: LlmRequest, endpoint: LlmEndpoint): JsonObject {
        val json = JsonObject()
        endpoint.model?.let { json["model"] = it }
        json["messages"] = JsonArray(request.messages.flatMap { message -> serializeMessage(message) })

        request.temperature?.let { json["temperature"] = it }
        request.maxTokens?.let { json["max_tokens"] = it }
        request.topP?.let { json["top_p"] = it }
        request.stopSequences
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { json["stop"] = JsonArray(it) }
        request.presencePenalty?.let { json["presence_penalty"] = it }
        request.frequencyPenalty?.let { json["frequency_penalty"] = it }

        addReasoningParams(json, request)

        if (request.responseSchema != null) {
            val schemaWrapper = JsonObject()
            schemaWrapper["name"] = request.responseSchema.title ?: "response"
            schemaWrapper["schema"] = request.responseSchema.toJsonObject()
            request.responseSchema.description?.let { schemaWrapper["description"] = it }
            json["response_format"] = JsonObject(mapOf("type" to "json_schema", "json_schema" to schemaWrapper))
        }

        // Guard against an empty list, not just null — OpenAI rejects "tools": [].
        if (!request.tools.isNullOrEmpty() && request.responseSchema == null) {
            json["tools"] = JsonArray(request.tools.map { tool ->
                JsonObject(
                    mapOf(
                        "type" to "function",
                        "function" to JsonObject(
                            mapOf(
                                "name" to tool.name,
                                "description" to tool.description,
                                "parameters" to tool.parameters.toJsonObject(),
                            )
                        ),
                    )
                )
            })
        }

        val toolChoice = request.toolChoice
        if (toolChoice != null && request.responseSchema == null) {
            json["tool_choice"] = when (toolChoice) {
                is ToolChoice.Auto -> "auto"
                is ToolChoice.None -> "none"
                is ToolChoice.Required -> "required"
                is ToolChoice.Specific -> JsonObject(
                    mapOf(
                        "type" to "function",
                        "function" to JsonObject(mapOf("name" to toolChoice.name)),
                    )
                )
            }
        }

        addProviderSpecificParams(json, request, endpoint)

        return request.applyExtraBodyParameters(json)
    }

    // --- Response parsing ---

    internal fun parseCompletionResponse(body: String, response: Response, endpoint: LlmEndpoint): LlmResponse {
        val json = body.asRequiredJsonObject()
        return parseCompletionJson(json, response, endpoint)
    }

    internal fun parseCompletionJson(json: JsonObject, response: Response, endpoint: LlmEndpoint): LlmResponse {
        val parsed = parseContentParts(json)
        val choice = json.reqArray("choices")[0].reqObj
        val usage = json.obj("usage")
        val finishReasonStr = choice.string("finish_reason")

        return LlmResponse(
            contentParts = parsed.parts,
            model = extractModel(json, endpoint),
            inputTokens = usage?.get("prompt_tokens")?.int,
            outputTokens = usage?.get("completion_tokens")?.int,
            finishReason = finishReasonStr?.let { mapFinishReason(it) },
            endpoint = endpoint,
            rateLimits = parseRateLimits(response),
            reasoning = parsed.reasoning,
        )
    }

    /**
     * Parse completion JSON without HTTP response headers (e.g., from batch results).
     */
    internal fun parseCompletionJsonNoHeaders(json: JsonObject, endpoint: LlmEndpoint? = null): LlmResponse {
        val parsed = parseContentParts(json)
        val choice = json.reqArray("choices")[0].reqObj
        val usage = json.obj("usage")
        val finishReasonStr = choice.string("finish_reason")

        return LlmResponse(
            contentParts = parsed.parts,
            model = json.string("model") ?: endpoint?.model ?: "",
            inputTokens = usage?.get("prompt_tokens")?.int,
            outputTokens = usage?.get("completion_tokens")?.int,
            finishReason = finishReasonStr?.let { mapFinishReason(it) },
            endpoint = endpoint,
            reasoning = parsed.reasoning,
        )
    }

    private data class ParsedContent(val parts: List<LlmContentPart>, val reasoning: String?)

    private fun parseContentParts(json: JsonObject): ParsedContent {
        val choice = json.reqArray("choices")[0].reqObj
        val message = choice.reqObj("message")
        val contentParts = mutableListOf<LlmContentPart>()

        message.string("content")?.let { contentParts.add(LlmContentPart.Text(it)) }

        message.array("tool_calls")?.let { toolCalls ->
            for (element in toolCalls) {
                val toolCall = element.reqObj
                val function = toolCall.reqObj("function")
                contentParts.add(
                    LlmContentPart.ToolUse(
                    id = toolCall.reqString("id"),
                    name = function.reqString("name"),
                    input = function.reqString("arguments").asRequiredJsonObject(),
                ))
            }
        }

        val reasoning = extractReasoningText(message)
        return ParsedContent(contentParts, reasoning)
    }

    private fun extractReasoningText(json: JsonObject): String? {
        val direct = json.string("reasoning_content") ?: json.string("reasoning")
        if (direct != null) {
            return direct
        }

        val parts = json.array("reasoning")
            ?: json.array("reasoning_details")
            ?: json.array("reasoning_summary")
            ?: return null

        return parts.items.mapNotNull { item ->
            val obj = item.obj
            obj?.string("text")
                ?: obj?.string("content")
                ?: obj?.string("summary")
                ?: item.string
        }.joinToString("").ifBlank { null }
    }

    // --- Message serialization ---

    private fun serializeMessage(message: LlmMessage): List<JsonObject> {
        if (message.role == PromptRole.TOOL) {
            return message.content.filterIsInstance<LlmContentPart.ToolResult>().map { part ->
                JsonObject(
                    mapOf(
                        "role" to "tool",
                        "tool_call_id" to part.toolUseId,
                        "content" to part.content,
                    )
                )
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
                JsonObject(
                    mapOf(
                        "id" to toolUse.id,
                        "type" to "function",
                        "function" to JsonObject(
                            mapOf(
                                "name" to toolUse.name,
                                "arguments" to toolUse.input.toJsonString(),
                            )
                        ),
                    )
                )
            })
        }

        return listOf(msgJson)
    }

    private fun serializeImagePart(part: LlmContentPart.Image): JsonObject {
        val url = when (val source = part.source) {
            is ImageSource.Base64 -> "data:${source.mediaType};base64,${source.data}"
            is ImageSource.Url -> source.url
        }
        return JsonObject(
            mapOf(
                "type" to "image_url",
                "image_url" to JsonObject(mapOf("url" to url)),
            )
        )
    }

    // --- Utilities ---

    companion object {
        internal val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun mapFinishReason(reason: String): FinishReason {
            return when (reason) {
                "stop" -> FinishReason.STOP
                "length" -> FinishReason.MAX_TOKENS
                "tool_calls" -> FinishReason.TOOL_USE
                "content_filter" -> FinishReason.CONTENT_FILTER
                else -> FinishReason.STOP
            }
        }
    }
}
