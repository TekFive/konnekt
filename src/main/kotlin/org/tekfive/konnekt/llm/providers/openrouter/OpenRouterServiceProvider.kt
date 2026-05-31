package org.tekfive.konnekt.llm.providers.openrouter

import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider

/**
 * ML service provider for the OpenRouter API.
 *
 * OpenRouter is a meta-router providing access to models across many providers
 * via an OpenAI-compatible API.
 */
object OpenRouterServiceProvider : OpenAICompatibleProvider() {
}
