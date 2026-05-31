package org.tekfive.konnekt.llm.providers.vllm

import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.LlmException
import org.tekfive.konnekt.llm.LlmRequest
import org.tekfive.konnekt.llm.RateLimits
import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider

/**
 * ML service provider for VLLM-compatible endpoints.
 *
 * Uses the OpenAI-compatible API that VLLM exposes. Requires a `baseUrl` on the
 * endpoint (no default — VLLM runs locally). API key is optional (VLLM can run
 * with or without `--api-key`). Model is optional (VLLM typically serves one model).
 */
object VllmServiceProvider : OpenAICompatibleProvider() {

    override fun validateEndpoint(endpoint: LlmEndpoint) {
        if (endpoint.resolvedBaseUrl == null) {
            throw LlmException("Base URL is required for VLLM endpoints")
        }
    }

    override fun extractModel(json: JsonObject, endpoint: LlmEndpoint): String {
        return json.string("model") ?: endpoint.model ?: ""
    }

    override fun parseRateLimits(response: okhttp3.Response): RateLimits? = null

    override fun addProviderSpecificParams(json: JsonObject, request: LlmRequest, endpoint: LlmEndpoint) {
        request.topK?.let { json["top_k"] = it }
    }
}
