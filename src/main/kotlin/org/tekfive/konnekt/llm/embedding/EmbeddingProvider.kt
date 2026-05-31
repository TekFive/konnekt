package org.tekfive.konnekt.llm.embedding

import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.providers.LlmServiceProvider

interface EmbeddingProvider : LlmServiceProvider {
    fun embed(request: EmbeddingRequest, endpoint: LlmEndpoint): EmbeddingResponse
}
