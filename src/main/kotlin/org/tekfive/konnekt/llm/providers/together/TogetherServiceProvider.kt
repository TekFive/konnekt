package org.tekfive.konnekt.llm.providers.together

import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider

/**
 * ML service provider for the Together AI API.
 *
 * Together AI hosts open-source models (Llama, Mixtral, etc.) via an OpenAI-compatible API.
 */
object TogetherServiceProvider : OpenAICompatibleProvider() {
}
