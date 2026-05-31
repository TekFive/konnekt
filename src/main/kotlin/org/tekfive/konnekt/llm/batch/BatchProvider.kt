package org.tekfive.konnekt.llm.batch

import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.providers.LlmServiceProvider

interface BatchProvider : LlmServiceProvider {
    fun submitBatch(items: List<MLBatchItem>, endpoint: LlmEndpoint): String
    fun getBatchStatus(batchId: String, endpoint: LlmEndpoint): MLBatchStatus
    fun getBatchResults(batchId: String, endpoint: LlmEndpoint): List<MLBatchResult>
}