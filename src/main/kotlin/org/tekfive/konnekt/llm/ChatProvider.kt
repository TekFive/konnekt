package org.tekfive.konnekt.llm

import org.tekfive.konnekt.llm.providers.LlmServiceProvider

interface ChatProvider : LlmServiceProvider {
    fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse
}
