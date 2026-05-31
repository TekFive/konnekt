package org.tekfive.konnekt.llm

import java.time.Instant

/**
 * Exception thrown when an ML service request fails.
 */
class LlmException(
    message: String,
    cause: Throwable? = null,
    val isRequestValidationError: Boolean = false,
    val isRateLimited: Boolean = false,
    val rateLimitResetAt: Instant? = null,
) : RuntimeException(message, cause)
