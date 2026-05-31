package org.tekfive.konnekt.llm.batch

/**
 * Callback invoked when a batch request completes or fails.
 */
fun interface MLBatchCallback {
    fun onComplete(batchId: String, results: List<MLBatchResult>)
}
