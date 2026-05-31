package org.tekfive.konnekt.llm

data class RateLimits(
    val remainingRequests: Int? = null,
    val remainingTokens: Int? = null,
    val resetRequests: String? = null,
    val resetTokens: String? = null,
)
