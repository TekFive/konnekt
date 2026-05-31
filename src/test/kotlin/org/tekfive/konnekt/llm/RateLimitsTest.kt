package org.tekfive.konnekt.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RateLimitsTest {

    @Test
    fun `rate limits with all fields`() {
        val limits = RateLimits(
            remainingRequests = 100,
            remainingTokens = 50000,
            resetRequests = "2026-03-26T15:00:00Z",
            resetTokens = "2026-03-26T15:00:00Z",
        )
        assertEquals(100, limits.remainingRequests)
        assertEquals(50000, limits.remainingTokens)
    }

    @Test
    fun `rate limits default to null`() {
        val limits = RateLimits()
        assertNull(limits.remainingRequests)
        assertNull(limits.remainingTokens)
        assertNull(limits.resetRequests)
        assertNull(limits.resetTokens)
    }
}
