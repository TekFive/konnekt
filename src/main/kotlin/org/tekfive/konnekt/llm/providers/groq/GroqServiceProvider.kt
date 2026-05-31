package org.tekfive.konnekt.llm.providers.groq

import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.providers.OpenAICompatibleProvider

/**
 * ML service provider for the Groq API.
 *
 * Groq provides ultra-fast inference via custom LPU hardware. OpenAI-compatible API.
 */
object GroqServiceProvider : OpenAICompatibleProvider() {
}
