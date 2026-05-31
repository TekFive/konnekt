package org.tekfive.konnekt.llm.batch

/**
 * Processing status of a batch request.
 */
enum class MLBatchStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED,
    ;
}
