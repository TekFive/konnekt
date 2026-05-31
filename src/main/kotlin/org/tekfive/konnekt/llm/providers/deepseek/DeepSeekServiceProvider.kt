package org.tekfive.konnekt.llm.providers.deepseek

import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider

/**
 * ML service provider for the DeepSeek API.
 *
 * DeepSeek uses an OpenAI-compatible API. All shared logic (request building,
 * response parsing, streaming, message serialization) is in [OpenAICompatibleProvider].
 */
object DeepSeekServiceProvider : OpenAICompatibleProvider() {
}
