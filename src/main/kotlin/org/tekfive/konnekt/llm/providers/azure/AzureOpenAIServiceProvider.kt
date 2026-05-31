package org.tekfive.konnekt.llm.providers.azure

import okhttp3.Request
import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.LlmException
import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider

/**
 * ML service provider for Azure OpenAI Service.
 *
 * Azure OpenAI uses deployment-based URLs and `api-key` header authentication
 * instead of Bearer tokens. The endpoint's `baseUrl` must be the Azure resource URL
 * (e.g., `https://{resource}.openai.azure.com`), and `model` is the deployment name.
 *
 * Example:
 * ```
 * Endpoint(
 *     provider = LlmServiceProvider.AZURE_OPENAI,
 *     model = "my-gpt4o-deployment",
 *     apiKey = "azure-api-key",
 *     baseUrl = "https://myresource.openai.azure.com",
 * )
 * ```
 */
object AzureOpenAIServiceProvider : OpenAICompatibleProvider() {

    private const val API_VERSION = "2024-10-21"

    override fun validateEndpoint(endpoint: LlmEndpoint) {
        if (endpoint.apiKey == null) throw LlmException("API key required for ${type.displayName}")
        if (endpoint.resolvedBaseUrl == null) throw LlmException("Base URL required for ${type.displayName} (e.g., https://{resource}.openai.azure.com)")
        if (endpoint.model == null) throw LlmException("Deployment name (model) required for ${type.displayName}")
    }

    override fun addAuthHeaders(builder: Request.Builder, endpoint: LlmEndpoint) {
        builder.header("api-key", endpoint.apiKey!!)
    }

    override fun chatCompletionsUrl(endpoint: LlmEndpoint): String {
        val baseUrl = endpoint.resolvedBaseUrl!!
        val deployment = endpoint.model!!
        return "$baseUrl/openai/deployments/$deployment/chat/completions?api-version=$API_VERSION"
    }
}
