package org.tekfive.konnekt.llm.providers.openai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.konnekt.llm.batch.BatchProvider
import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.embedding.EmbeddingProvider
import org.tekfive.konnekt.llm.embedding.EmbeddingRequest
import org.tekfive.konnekt.llm.embedding.EmbeddingResponse
import org.tekfive.konnekt.llm.batch.MLBatchItem
import org.tekfive.konnekt.llm.batch.MLBatchResult
import org.tekfive.konnekt.llm.batch.MLBatchStatus
import org.tekfive.konnekt.llm.LlmException
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider

/**
 * ML service provider for the OpenAI Chat Completions API.
 *
 * Chat, streaming, request building, and response parsing are inherited from
 * [OpenAICompatibleProvider]. This class adds batch and embedding support
 * specific to the OpenAI API.
 */
object OpenAIServiceProvider : OpenAICompatibleProvider(), BatchProvider, EmbeddingProvider {

    // --- Embeddings ---

    override fun embed(request: EmbeddingRequest, endpoint: LlmEndpoint): EmbeddingResponse {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")

        val body = JsonObject(mapOf(
            "input" to JsonArray(request.input),
            "model" to (endpoint.model ?: ""),
        ))

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/embeddings")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val client = clientPool.getClient(endpoint)
        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from OpenAI Embeddings API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("OpenAI Embeddings API error ${response.code}: $responseBody")
        }

        return parseEmbeddingResponse(responseBody)
    }

    internal fun parseEmbeddingResponse(body: String): EmbeddingResponse {
        val json = body.asRequiredJsonObject()
        val data = json.reqArray("data")
        val embeddings = data.items.sortedBy { it["index"].int ?: 0 }.map { element ->
            val embeddingArray = element.reqObj.reqArray("embedding")
            FloatArray(embeddingArray.size) { i -> embeddingArray[i].double?.toFloat() ?: 0f }
        }
        val model = json.reqString("model")
        val usage = json.obj("usage")

        return EmbeddingResponse(
            embeddings = embeddings,
            model = model,
            inputTokens = usage?.get("prompt_tokens")?.int,
        )
    }

    // --- Batch ---

    override fun submitBatch(items: List<MLBatchItem>, endpoint: LlmEndpoint): String {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val inputFileId = uploadBatchInputFile(items, endpoint)

        val batchBody = JsonObject(mapOf(
            "input_file_id" to inputFileId,
            "endpoint" to "/v1/chat/completions",
            "completion_window" to "24h",
        ))

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/batches")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(batchBody.toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val client = clientPool.getClient(endpoint)
        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from OpenAI Batch API")

        if (response.code == 429) {
            throw LlmException(
                "${type.displayName} rate limited: $responseBody",
                isRateLimited = true,
            )
        }

        if (!response.isSuccessful) {
            throw LlmException("OpenAI Batch API error ${response.code}: $responseBody")
        }

        val json = responseBody.asRequiredJsonObject()
        return json.reqString("id")
    }

    override fun getBatchStatus(batchId: String, endpoint: LlmEndpoint): MLBatchStatus {
        val json = fetchBatchJson(batchId, endpoint)
        return parseBatchStatus(json.reqString("status"))
    }

    override fun getBatchResults(batchId: String, endpoint: LlmEndpoint): List<MLBatchResult> {
        val batchJson = fetchBatchJson(batchId, endpoint)
        val outputFileId = batchJson.string("output_file_id")
            ?: throw LlmException("Batch $batchId has no output file")

        val jsonl = downloadFile(outputFileId, endpoint)
        return parseBatchResultsJsonl(jsonl)
    }

    internal fun buildBatchInputJsonl(items: List<MLBatchItem>, endpoint: LlmEndpoint): String {
        return items.joinToString("\n") { item ->
            JsonObject(mapOf(
                "custom_id" to item.id,
                "method" to "POST",
                "url" to "/v1/chat/completions",
                "body" to buildRequestJson(item.request, endpoint),
            )).toJsonString()
        }
    }

    internal fun parseBatchStatus(status: String): MLBatchStatus {
        return when (status) {
            "validating", "in_progress", "finalizing" -> MLBatchStatus.IN_PROGRESS
            "completed" -> MLBatchStatus.COMPLETED
            "failed" -> MLBatchStatus.FAILED
            "expired" -> MLBatchStatus.EXPIRED
            "cancelling", "cancelled" -> MLBatchStatus.CANCELLED
            else -> MLBatchStatus.FAILED
        }
    }

    internal fun parseBatchResultsJsonl(jsonl: String): List<MLBatchResult> {
        return jsonl.lines().filter { it.isNotBlank() }.map { line ->
            val json = line.asRequiredJsonObject()
            val customId = json.reqString("custom_id")
            val responseObj = json.obj("response")
            val error = json.obj("error")

            if (responseObj != null && responseObj["status_code"].int == 200) {
                val body = responseObj.reqObj("body")
                MLBatchResult(
                    id = customId,
                    response = parseCompletionJsonNoHeaders(body),
                    error = null,
                )
            } else {
                val errorMessage = error?.string("message")
                    ?: responseObj?.string("body")
                    ?: "Unknown error"
                MLBatchResult(
                    id = customId,
                    response = null,
                    error = errorMessage,
                )
            }
        }
    }

    private fun uploadBatchInputFile(items: List<MLBatchItem>, endpoint: LlmEndpoint): String {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")
        val jsonlContent = buildBatchInputJsonl(items, endpoint)

        val fileBody = MultipartBody.Builder()
            .setType(MultipartBody.Companion.FORM)
            .addFormDataPart("purpose", "batch")
            .addFormDataPart(
                "file", "batch_input.jsonl",
                jsonlContent.toRequestBody("application/jsonl".toMediaType()),
            )
            .build()

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/files")
            .header("Authorization", "Bearer $apiKey")
            .post(fileBody)
            .build()

        val client = clientPool.getClient(endpoint)
        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from OpenAI Files API")

        if (!response.isSuccessful) {
            throw LlmException("OpenAI Files API error ${response.code}: $responseBody")
        }

        val json = responseBody.asRequiredJsonObject()
        return json.reqString("id")
    }

    private fun fetchBatchJson(batchId: String, endpoint: LlmEndpoint): JsonObject {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/batches/$batchId")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        val client = clientPool.getClient(endpoint)
        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from OpenAI Batch API")

        if (!response.isSuccessful) {
            throw LlmException("OpenAI Batch API error ${response.code}: $responseBody")
        }

        return responseBody.asRequiredJsonObject()
    }

    private fun downloadFile(fileId: String, endpoint: LlmEndpoint): String {
        val apiKey = endpoint.apiKey ?: throw LlmException("API key required for ${type.displayName}")
        val baseUrl = endpoint.resolvedBaseUrl ?: throw LlmException("Base URL required for ${type.displayName}")

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/files/$fileId/content")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        val client = clientPool.getClient(endpoint)
        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from OpenAI Files API")

        if (!response.isSuccessful) {
            throw LlmException("OpenAI Files API error ${response.code}: $responseBody")
        }

        return responseBody
    }
}
