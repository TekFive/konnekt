package org.tekfive.konnekt.llm.batch

import org.tekfive.konnekt.llm.LlmRequest

/**
 * A single item in a batch request, identified by a custom [id].
 */
data class MLBatchItem(
    val id: String,
    val request: LlmRequest,
)
