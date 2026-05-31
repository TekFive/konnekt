package org.tekfive.konnekt.llm

import org.tekfive.konnekt.llm.providers.LlmServiceProvider
import org.tekfive.konnekt.llm.providers.antrophic.AnthropicServiceProvider
import org.tekfive.konnekt.llm.providers.azure.AzureOpenAIServiceProvider
import org.tekfive.konnekt.llm.providers.deepseek.DeepSeekServiceProvider
import org.tekfive.konnekt.llm.providers.fireworks.FireworksServiceProvider
import org.tekfive.konnekt.llm.providers.gemini.GeminiServiceProvider
import org.tekfive.konnekt.llm.providers.grok.GrokServiceProvider
import org.tekfive.konnekt.llm.providers.groq.GroqServiceProvider
import org.tekfive.konnekt.llm.providers.mistral.MistralServiceProvider
import org.tekfive.konnekt.llm.providers.openai.OpenAIServiceProvider
import org.tekfive.konnekt.llm.providers.openrouter.OpenRouterServiceProvider
import org.tekfive.konnekt.llm.providers.perplexity.PerplexityServiceProvider
import org.tekfive.konnekt.llm.providers.together.TogetherServiceProvider
import org.tekfive.konnekt.llm.providers.bedrock.BedrockServiceProvider
import org.tekfive.konnekt.llm.providers.cohere.CohereServiceProvider
import org.tekfive.konnekt.llm.providers.vllm.VllmServiceProvider

/**
 * Supported ML service providers. Each provider is associated with a model ID prefix
 * used to automatically determine the provider from a model identifier string.
 */
enum class LlmServiceProviderType(
    val displayName: String,
    val modelPrefix: String,
    val defaultBaseUrl: String?,
    val serviceProvider: LlmServiceProvider,
) {
    ANTHROPIC("Anthropic", "claude", "https://api.anthropic.com", AnthropicServiceProvider),
    OPENAI("OpenAI", "gpt", "https://api.openai.com", OpenAIServiceProvider),
    AZURE_OPENAI("Azure OpenAI", "azure", null, AzureOpenAIServiceProvider),
    GOOGLE("Google", "gemini", "https://generativelanguage.googleapis.com", GeminiServiceProvider),
    GROK("Grok", "grok", "https://api.x.ai", GrokServiceProvider),
    DEEPSEEK("DeepSeek", "deepseek", "https://api.deepseek.com", DeepSeekServiceProvider),
    MISTRAL("Mistral", "mistral", "https://api.mistral.ai", MistralServiceProvider),
    TOGETHER("Together AI", "together", "https://api.together.xyz", TogetherServiceProvider),
    FIREWORKS("Fireworks AI", "accounts/fireworks", "https://api.fireworks.ai", FireworksServiceProvider),
    GROQ("Groq", "groq", "https://api.groq.com/openai", GroqServiceProvider),
    PERPLEXITY("Perplexity", "sonar", "https://api.perplexity.ai", PerplexityServiceProvider),
    OPENROUTER("OpenRouter", "openrouter", "https://openrouter.ai/api", OpenRouterServiceProvider),
    COHERE("Cohere", "command", "https://api.cohere.com", CohereServiceProvider),
    VLLM("VLLM", "vllm", null, VllmServiceProvider),
    BEDROCK("AWS Bedrock", "bedrock", null, BedrockServiceProvider),
    ;

    companion object {
        fun forModel(modelId: String): LlmServiceProviderType {
            return entries.firstOrNull { modelId.startsWith(it.modelPrefix) }
                ?: throw IllegalArgumentException(
                    "Cannot determine provider for model: $modelId. " +
                    "Model ID must start with one of: ${entries.joinToString { "'${it.modelPrefix}'" }}"
                )
        }
    }
}