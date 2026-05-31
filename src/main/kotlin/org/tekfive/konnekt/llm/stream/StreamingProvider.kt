package org.tekfive.konnekt.llm.stream

import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.LlmRequest
import org.tekfive.konnekt.llm.providers.LlmServiceProvider

interface StreamingProvider : LlmServiceProvider {
    fun chatStream(request: LlmRequest, endpoint: LlmEndpoint, listener: StreamListener)
}
