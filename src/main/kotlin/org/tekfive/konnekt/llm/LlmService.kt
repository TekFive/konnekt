package org.tekfive.konnekt.llm

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.schema.JsonSchema
import org.tekfive.konnekt.llm.batch.BatchProvider
import org.tekfive.konnekt.llm.batch.MLBatchItem
import org.tekfive.konnekt.llm.batch.MLBatchResult
import org.tekfive.konnekt.llm.batch.MLBatchStatus
import org.tekfive.konnekt.llm.embedding.EmbeddingProvider
import org.tekfive.konnekt.llm.embedding.EmbeddingRequest
import org.tekfive.konnekt.llm.embedding.EmbeddingResponse
import org.tekfive.konnekt.llm.providers.LlmServiceProvider
import org.tekfive.konnekt.llm.stream.StreamListener
import org.tekfive.konnekt.llm.stream.StreamingProvider
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

object LlmService {

    private val cooldowns = ConcurrentHashMap<Int, Instant>()
    private var providerOverrides = ConcurrentHashMap<LlmServiceProviderType, LlmServiceProvider>()

    // --- Chat ---

    fun chat(request: LlmRequest): LlmResponse {
        return executeWithFallback(request) { endpoint ->
            val chatProvider = resolveProvider(endpoint) as? ChatProvider
                ?: throw LlmException("Provider ${endpoint.providerType.displayName} does not support chat")
            chatProvider.chat(request, endpoint)
        }
    }

    fun chat(prompt: String, endpoint: LlmEndpoint, temperature: Double = 0.7): String {
        val request = LlmRequest(
            messages = listOf(LlmMessage.userMessage(prompt)),
            endpoint = endpoint,
            temperature = temperature,
        )
        return chat(request).content
    }

    fun chat(systemPrompt: String, userContent: String, endpoint: LlmEndpoint, temperature: Double = 0.7): LlmResponse {
        val request = LlmRequest(
            messages = listOf(LlmMessage.systemMessage(systemPrompt), LlmMessage.userMessage(userContent)),
            endpoint = endpoint,
            temperature = temperature,
        )
        return chat(request)
    }

    fun structuredChat(
        systemPrompt: String,
        userContent: String,
        responseSchema: JsonSchema,
        endpoint: LlmEndpoint,
        temperature: Double = 0.7,
    ): JsonObject {
        val request = LlmRequest(
            messages = listOf(LlmMessage.systemMessage(systemPrompt), LlmMessage.userMessage(userContent)),
            endpoint = endpoint,
            temperature = temperature,
            responseSchema = responseSchema,
        )
        return chat(request).contentAsJson()
            ?: throw LlmException("LLM service returned response but it was not valid JSON.")
    }

    // --- Streaming ---

    fun chatStream(request: LlmRequest, listener: StreamListener) {
        val endpoint = request.endpoint
        val streamingProvider = resolveProvider(endpoint) as? StreamingProvider
            ?: throw LlmException("Provider ${endpoint.providerType.displayName} does not support streaming")
        streamingProvider.chatStream(request, endpoint, listener)
    }

    // --- Embeddings ---

    fun embed(request: EmbeddingRequest, endpoint: LlmEndpoint): EmbeddingResponse {
        val embeddingProvider = resolveProvider(endpoint) as? EmbeddingProvider
            ?: throw LlmException("Provider ${endpoint.providerType.displayName} does not support embeddings")
        return embeddingProvider.embed(request, endpoint)
    }

    // --- Batch ---

    fun submitBatch(items: List<MLBatchItem>, endpoint: LlmEndpoint): String {
        val batchProvider = resolveProvider(endpoint) as? BatchProvider
            ?: throw LlmException("Provider ${endpoint.providerType.displayName} does not support batch messaging.")

        return batchProvider.submitBatch(items, endpoint)
    }

    fun getBatchStatus(batchId: String, endpoint: LlmEndpoint): MLBatchStatus {
        val batchProvider = resolveProvider(endpoint) as? BatchProvider
            ?: throw LlmException("Provider ${endpoint.providerType.displayName} does not support batch messaging.")

        return batchProvider.getBatchStatus(batchId, endpoint)
    }

    fun getBatchResults(batchId: String, endpoint: LlmEndpoint): List<MLBatchResult> {
        val batchProvider = resolveProvider(endpoint) as? BatchProvider
            ?: throw LlmException("Provider ${endpoint.providerType.displayName} does not support batch messaging.")

        return batchProvider.getBatchResults(batchId, endpoint)
    }

    // --- Fallback ---

    private fun executeWithFallback(
        request: LlmRequest,
        action: (LlmEndpoint) -> LlmResponse,
    ): LlmResponse {
        val allEndpoints = listOf(request.endpoint) + request.fallbackEndpoints
        var lastException: LlmException? = null
        var triedAny = false

        for (endpoint in allEndpoints) {
            if (isOnCooldown(endpoint)) continue
            triedAny = true

            try {
                return action(endpoint)
            } catch (e: LlmException) {
                lastException = e
                if (e.isRateLimited) recordCooldown(endpoint, e)
                val shouldFallback = request.shouldFallback ?: ::defaultShouldFallback
                if (!shouldFallback(e)) throw e
            }
        }

        if (!triedAny) {
            val earliestCooldown = allEndpoints
                .mapNotNull { ep -> cooldowns[ep.hashCode()]?.let { ep to it } }
                .minByOrNull { it.second }
                ?.first

            if (earliestCooldown != null) {
                return action(earliestCooldown)
            }
        }

        throw lastException ?: LlmException("No endpoints available")
    }

    private fun defaultShouldFallback(e: LlmException): Boolean {
        return !e.isRequestValidationError
    }

    // --- Cooldowns ---

    private fun isOnCooldown(endpoint: LlmEndpoint): Boolean {
        val expiry = cooldowns[endpoint.hashCode()] ?: return false
        if (Instant.now().isAfter(expiry)) {
            cooldowns.remove(endpoint.hashCode())
            return false
        }
        return true
    }

    private fun recordCooldown(endpoint: LlmEndpoint, e: LlmException) {
        val resetTime = e.rateLimitResetAt ?: Instant.now().plusSeconds(60)
        cooldowns[endpoint.hashCode()] = resetTime
    }

    // --- Provider resolution ---

    private fun resolveProvider(endpoint: LlmEndpoint): LlmServiceProvider {
        return providerOverrides[endpoint.providerType] ?: endpoint.providerType.serviceProvider
    }

    // --- Test support ---

    internal fun overrideProvider(provider: LlmServiceProviderType, serviceProvider: LlmServiceProvider) {
        providerOverrides[provider] = serviceProvider
    }

    internal fun clearOverrides() {
        providerOverrides.clear()
    }

    internal fun clearCooldowns() {
        cooldowns.clear()
    }
}
