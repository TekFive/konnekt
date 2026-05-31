package org.tekfive.konnekt.llm.providers.grok

import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider

/**
 * ML service provider for the xAI Grok API.
 *
 * Grok uses an OpenAI-compatible API. All shared logic (request building,
 * response parsing, streaming, message serialization) is in [OpenAICompatibleProvider].
 */
object GrokServiceProvider : OpenAICompatibleProvider() {
}
