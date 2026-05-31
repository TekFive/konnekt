package org.tekfive.konnekt.llm

import org.tekfive.ack.Ack

class EndpointBuilder(private vararg val providers: LlmServiceProviderType) {

    fun build(): List<LlmEndpoint> {
        return providers.mapNotNull { provider ->
            val apiKeyProp = Ack.secret("${provider.configName}_API_KEY", namespace = NAMESPACE, description = "API key for the ${provider.configName} LLM provider.")
            val apiKey = apiKeyProp.orNull() ?: return@mapNotNull null

            val modelProp = Ack.string("${provider.configName}_MODEL", namespace = NAMESPACE, description = "Default model name for the ${provider.configName} LLM provider.")
            val baseUrlProp = Ack.string("${provider.configName}_BASE_URL", namespace = NAMESPACE, description = "Base URL override for the ${provider.configName} LLM provider.")

            LlmEndpoint(
                providerType = provider,
                model = modelProp.orNull(),
                apiKey = apiKey,
                baseUrl = baseUrlProp.orNull(),
            )
        }
    }

    companion object {
        private const val NAMESPACE = "KONNEKT"
    }
}

private val LlmServiceProviderType.configName: String
    get() = when (this) {
        LlmServiceProviderType.ANTHROPIC -> "ANTHROPIC"
        LlmServiceProviderType.OPENAI -> "OPENAI"
        LlmServiceProviderType.AZURE_OPENAI -> "AZURE_OPENAI"
        LlmServiceProviderType.GOOGLE -> "GEMINI"
        LlmServiceProviderType.GROK -> "GROK"
        LlmServiceProviderType.DEEPSEEK -> "DEEPSEEK"
        LlmServiceProviderType.MISTRAL -> "MISTRAL"
        LlmServiceProviderType.TOGETHER -> "TOGETHER"
        LlmServiceProviderType.FIREWORKS -> "FIREWORKS"
        LlmServiceProviderType.GROQ -> "GROQ"
        LlmServiceProviderType.PERPLEXITY -> "PERPLEXITY"
        LlmServiceProviderType.OPENROUTER -> "OPENROUTER"
        LlmServiceProviderType.COHERE -> "COHERE"
        LlmServiceProviderType.VLLM -> "VLLM"
        LlmServiceProviderType.BEDROCK -> "BEDROCK"
    }
