package org.tekfive.konnekt.llm.providers.mistral

import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider

/**
 * ML service provider for the Mistral AI API.
 *
 * Mistral uses an OpenAI-compatible API. All shared logic (request building,
 * response parsing, streaming, message serialization) is in [OpenAICompatibleProvider].
 */
object MistralServiceProvider : OpenAICompatibleProvider() {
}
