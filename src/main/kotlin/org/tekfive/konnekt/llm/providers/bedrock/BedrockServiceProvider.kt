package org.tekfive.konnekt.llm.providers.bedrock

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
import org.tekfive.konnekt.llm.LlmException
import org.tekfive.konnekt.llm.LlmRequest
import org.tekfive.konnekt.llm.LlmReasoningEffort
import org.tekfive.konnekt.llm.LlmResponse
import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.PromptRole
import org.tekfive.konnekt.llm.content.ToolChoice
import org.tekfive.konnekt.llm.stream.StreamListener
import org.tekfive.konnekt.llm.stream.StreamingProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * LLM service provider for AWS Bedrock using the Converse API.
 *
 * Authentication uses AWS Signature V4. The endpoint's [LlmEndpoint.apiKey] must be formatted
 * as `{accessKeyId}:{secretAccessKey}`, and [LlmEndpoint.baseUrl] must contain the region
 * (e.g., `https://bedrock-runtime.us-east-1.amazonaws.com`).
 *
 * Uses the Converse API endpoints:
 * - `POST /model/{modelId}/converse` for synchronous chat
 * - `POST /model/{modelId}/converse-stream` for streaming chat
 */
object BedrockServiceProvider : ChatProvider, StreamingProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    // --- Chat ---

    override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse {
        val (accessKeyId, secretAccessKey) = parseCredentials(endpoint)
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val model = endpoint.model ?: throw LlmException("Model required for ${type.displayName}")
        val region = AwsSignatureV4.extractRegion(baseUrl)

        val body = buildRequestJson(request, endpoint)
        val bodyBytes = body.toJsonString().toByteArray(Charsets.UTF_8)
        val url = "$baseUrl/model/$model/converse"

        val headers = mapOf("Content-Type" to "application/json")
        val signedHeaders = AwsSignatureV4.sign(
            method = "POST",
            url = url,
            headers = headers,
            body = bodyBytes,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            region = region,
        )

        val httpRequest = Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
                signedHeaders.forEach { (k, v) -> header(k, v) }
            }
            .post(bodyBytes.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from Bedrock API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("Bedrock API error ${response.code}: $responseBody")
        }

        return parseResponse(responseBody, endpoint)
    }

    // --- Streaming ---

    override fun chatStream(request: LlmRequest, endpoint: LlmEndpoint, listener: StreamListener) {
        val (accessKeyId, secretAccessKey) = parseCredentials(endpoint)
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val model = endpoint.model ?: throw LlmException("Model required for ${type.displayName}")
        val region = AwsSignatureV4.extractRegion(baseUrl)

        val body = buildRequestJson(request, endpoint)
        val bodyBytes = body.toJsonString().toByteArray(Charsets.UTF_8)
        val url = "$baseUrl/model/$model/converse-stream"

        val headers = mapOf("Content-Type" to "application/json")
        val signedHeaders = AwsSignatureV4.sign(
            method = "POST",
            url = url,
            headers = headers,
            body = bodyBytes,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            region = region,
        )

        val httpRequest = Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
                signedHeaders.forEach { (k, v) -> header(k, v) }
            }
            .post(bodyBytes.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body
                ?: throw LlmException("Empty response from Bedrock API")

            if (response.code == 429) {
                val errorBody = responseBody.string()
                throw LlmException(
                    "${type.displayName} rate limited: $errorBody",
                    isRateLimited = true,
                )
            }

            if (!response.isSuccessful) {
                val errorBody = responseBody.string()
                throw LlmException("Bedrock API error ${response.code}: $errorBody")
            }

            var inputTokens: Int? = null
            var outputTokens: Int? = null
            var finishReason: FinishReason? = null
            val contentParts = mutableListOf<LlmContentPart>()
            val textBuffer = StringBuilder()
            val reasoningBuffer = StringBuilder()

            val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))

            reader.use {
                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("{")) {
                        val json = line.asRequiredJsonObject()

                        // contentBlockDelta with text
                        val delta = json.obj("contentBlockDelta")
                        if (delta != null) {
                            val innerDelta = delta.obj("delta")
                            val text = innerDelta?.string("text")
                            if (text != null) {
                                textBuffer.append(text)
                                listener.onToken(text)
                            }
                            val reasoningText = innerDelta?.obj("reasoningContent")?.let { extractReasoningText(it) }
                            if (reasoningText != null) {
                                reasoningBuffer.append(reasoningText)
                                listener.onReasoningToken(reasoningText)
                            }
                        }

                        // metadata with usage
                        val metadata = json.obj("metadata")
                        if (metadata != null) {
                            val usage = metadata.obj("usage")
                            inputTokens = usage?.get("inputTokens")?.int
                            outputTokens = usage?.get("outputTokens")?.int
                        }

                        // messageStop with stopReason
                        val messageStop = json.obj("messageStop")
                        if (messageStop != null) {
                            val stopReason = messageStop.string("stopReason")
                            if (stopReason != null) {
                                finishReason = mapStopReason(stopReason)
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }

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
                reasoning = reasoningBuffer.takeIf { it.isNotEmpty() }?.toString(),
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

        // System messages go in top-level "system" field
        val systemMessage = request.messages.firstOrNull { it.role == PromptRole.SYSTEM }
        val otherMessages = request.messages.filter { it.role != PromptRole.SYSTEM }

        if (systemMessage != null) {
            json["system"] = JsonArray(listOf(JsonObject(mapOf("text" to systemMessage.text))))
        }

        // Messages
        json["messages"] = JsonArray(otherMessages.map { msg ->
            val msgJson = JsonObject()
            msgJson["role"] = if (msg.role == PromptRole.TOOL) "user" else msg.role.wireValue
            msgJson["content"] = serializeContent(msg.content)
            msgJson
        })

        // Inference config
        val inferenceConfig = JsonObject()
        inferenceConfig["temperature"] = request.temperature ?: 0.7
        inferenceConfig["maxTokens"] = request.maxTokens ?: 4096

        if (request.topP != null) {
            inferenceConfig["topP"] = request.topP
        }
        if (request.stopSequences != null) {
            inferenceConfig["stopSequences"] = JsonArray(request.stopSequences)
        }
        json["inferenceConfig"] = inferenceConfig

        // A null effort means the provider default (no thinking field); NONE disables explicitly.
        if (request.reasoningEffort != null) {
            val thinking = when (request.reasoningEffort) {
                LlmReasoningEffort.NONE -> JsonObject(mapOf("type" to "disabled"))
                LlmReasoningEffort.LOW -> JsonObject(mapOf("type" to "enabled", "budget_tokens" to 1024))
                LlmReasoningEffort.MEDIUM -> JsonObject(mapOf("type" to "enabled", "budget_tokens" to 4096))
                LlmReasoningEffort.HIGH -> JsonObject(mapOf("type" to "enabled", "budget_tokens" to 16384))
            }
            json["additionalModelRequestFields"] = JsonObject(mapOf("thinking" to thinking))
        }

        // Tools
        if (request.responseSchema != null) {
            val schemaName = request.responseSchema.title ?: "response"
            val toolSpec = JsonObject(
                mapOf(
                    "toolSpec" to JsonObject(
                        mapOf(
                            "name" to schemaName,
                            "description" to (request.responseSchema.description ?: ""),
                            "inputSchema" to JsonObject(
                                mapOf("json" to request.responseSchema.toJsonObject())
                            ),
                        )
                    )
                )
            )
            json["toolConfig"] = JsonObject(
                mapOf(
                    "tools" to JsonArray(listOf(toolSpec)),
                    "toolChoice" to JsonObject(
                        mapOf(
                            "tool" to JsonObject(mapOf("name" to schemaName))
                        )
                    ),
                )
            )
        } else if (request.tools != null) {
            val tools = JsonArray(request.tools.map { tool ->
                JsonObject(
                    mapOf(
                        "toolSpec" to JsonObject(
                            mapOf(
                                "name" to tool.name,
                                "description" to tool.description,
                                "inputSchema" to JsonObject(
                                    mapOf("json" to tool.parameters.toJsonObject())
                                ),
                            )
                        )
                    )
                )
            })
            val toolConfig = JsonObject(mapOf("tools" to tools))

            if (request.toolChoice != null) {
                when (request.toolChoice) {
                    is ToolChoice.Auto -> toolConfig["toolChoice"] = JsonObject(mapOf("auto" to JsonObject()))
                    is ToolChoice.None -> { /* omit toolChoice */ }
                    is ToolChoice.Required -> toolConfig["toolChoice"] = JsonObject(mapOf("any" to JsonObject()))
                    is ToolChoice.Specific -> toolConfig["toolChoice"] = JsonObject(
                        mapOf(
                            "tool" to JsonObject(mapOf("name" to request.toolChoice.name))
                        )
                    )
                }
            }

            json["toolConfig"] = toolConfig
        }

        return request.applyExtraBodyParameters(json)
    }

    // --- Response parsing ---

    internal fun parseResponse(body: String, endpoint: LlmEndpoint): LlmResponse {
        val json = body.asRequiredJsonObject()

        val output = json.reqObj("output")
        val message = output.reqObj("message")
        val content = message.reqArray("content")
        val contentParts = mutableListOf<LlmContentPart>()
        val reasoningParts = mutableListOf<String>()

        for (element in content) {
            val block = element.reqObj
            val text = block.string("text")
            if (text != null) {
                contentParts.add(LlmContentPart.Text(text))
                continue
            }
            val reasoningContent = block.obj("reasoningContent")
            if (reasoningContent != null) {
                extractReasoningText(reasoningContent)?.let { reasoningParts.add(it) }
                continue
            }
            val toolUse = block.obj("toolUse")
            if (toolUse != null) {
                contentParts.add(
                    LlmContentPart.ToolUse(
                        id = toolUse.reqString("toolUseId"),
                        name = toolUse.reqString("name"),
                        input = toolUse.reqObj("input"),
                    )
                )
            }
        }

        val usage = json.obj("usage")
        val stopReason = json.string("stopReason")
        val model = endpoint.model ?: "unknown"

        return LlmResponse(
            contentParts = contentParts,
            model = model,
            inputTokens = usage?.get("inputTokens")?.int,
            outputTokens = usage?.get("outputTokens")?.int,
            finishReason = stopReason?.let { mapStopReason(it) },
            endpoint = endpoint,
            reasoning = reasoningParts.ifEmpty { null }?.joinToString("\n"),
        )
    }

    // --- Helpers ---

    internal fun parseCredentials(endpoint: LlmEndpoint): Pair<String, String> {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for AWS Bedrock")
        val parts = apiKey.split(":", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw LlmException(
                "Invalid Bedrock API key format. Expected 'accessKeyId:secretAccessKey', got: ${apiKey.take(20)}..."
            )
        }
        return Pair(parts[0], parts[1])
    }

    private fun mapStopReason(stopReason: String): FinishReason {
        return when (stopReason) {
            "end_turn" -> FinishReason.STOP
            "max_tokens" -> FinishReason.MAX_TOKENS
            "tool_use" -> FinishReason.TOOL_USE
            "content_filtered" -> FinishReason.CONTENT_FILTER
            else -> FinishReason.STOP
        }
    }

    private fun extractReasoningText(reasoningContent: JsonObject): String? {
        return reasoningContent.string("text")
            ?: reasoningContent.obj("reasoningText")?.string("text")
            ?: reasoningContent.string("reasoningText")
    }

    private fun serializeContent(parts: List<LlmContentPart>): JsonArray {
        return JsonArray(parts.map { part -> serializeContentPart(part) })
    }

    private fun serializeContentPart(part: LlmContentPart): JsonObject {
        return when (part) {
            is LlmContentPart.Text -> JsonObject(mapOf("text" to part.text))
            is LlmContentPart.ToolUse -> JsonObject(
                mapOf(
                    "toolUse" to JsonObject(
                        mapOf(
                            "toolUseId" to part.id,
                            "name" to part.name,
                            "input" to part.input,
                        )
                    )
                )
            )
            is LlmContentPart.ToolResult -> JsonObject(
                mapOf(
                    "toolResult" to JsonObject(
                        mapOf(
                            "toolUseId" to part.toolUseId,
                            "content" to JsonArray(
                                listOf(JsonObject(mapOf("text" to part.content)))
                            ),
                        )
                    )
                )
            )
            is LlmContentPart.Image -> throw LlmException("Bedrock Converse API image support requires base64 in document blocks")
            is LlmContentPart.Document -> throw LlmException("Bedrock Converse API document support not yet implemented")
            is LlmContentPart.Audio -> throw LlmException("Bedrock does not support audio content")
        }
    }
}
