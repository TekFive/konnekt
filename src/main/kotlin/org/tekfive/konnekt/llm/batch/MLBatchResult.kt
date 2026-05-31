package org.tekfive.konnekt.llm.batch

import org.tekfive.konnekt.llm.LlmResponse

/**
 * The result of a single item in a batch, matched to its original [id].
 */
data class MLBatchResult(
    val id: String,
    val response: LlmResponse?,
    val error: String?,
) {
    val isSuccess: Boolean get() = response != null
}
