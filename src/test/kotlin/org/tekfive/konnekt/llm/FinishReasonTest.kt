// src/test/kotlin/org/tekfive/konnekt/ai/FinishReasonTest.kt
package org.tekfive.konnekt.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinishReasonTest {

    @Test
    fun `wireValue matches expected strings`() {
        assertEquals("stop", FinishReason.STOP.wireValue)
        assertEquals("max_tokens", FinishReason.MAX_TOKENS.wireValue)
        assertEquals("tool_use", FinishReason.TOOL_USE.wireValue)
        assertEquals("content_filter", FinishReason.CONTENT_FILTER.wireValue)
    }

    @Test
    fun `fromWireValue resolves known values`() {
        assertEquals(FinishReason.STOP, FinishReason.fromWireValue("stop"))
        assertEquals(FinishReason.MAX_TOKENS, FinishReason.fromWireValue("max_tokens"))
        assertEquals(FinishReason.TOOL_USE, FinishReason.fromWireValue("tool_use"))
        assertEquals(FinishReason.CONTENT_FILTER, FinishReason.fromWireValue("content_filter"))
    }

    @Test
    fun `fromWireValue returns STOP for unknown values`() {
        assertEquals(FinishReason.STOP, FinishReason.fromWireValue("unknown_value"))
        assertEquals(FinishReason.STOP, FinishReason.fromWireValue(""))
    }

    @Test
    fun `fromWireValueOrNull returns null for unknown values`() {
        assertNull(FinishReason.fromWireValueOrNull("unknown_value"))
        assertNull(FinishReason.fromWireValueOrNull(null))
    }

    @Test
    fun `fromWireValueOrNull resolves known values`() {
        assertEquals(FinishReason.STOP, FinishReason.fromWireValueOrNull("stop"))
        assertEquals(FinishReason.TOOL_USE, FinishReason.fromWireValueOrNull("tool_use"))
    }
}
