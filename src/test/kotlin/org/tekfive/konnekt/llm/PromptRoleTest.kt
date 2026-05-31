package org.tekfive.konnekt.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class PromptRoleTest {

    @Test
    fun `wireValue matches expected strings`() {
        assertEquals("system", PromptRole.SYSTEM.wireValue)
        assertEquals("user", PromptRole.USER.wireValue)
        assertEquals("assistant", PromptRole.ASSISTANT.wireValue)
        assertEquals("tool", PromptRole.TOOL.wireValue)
    }

    @Test
    fun `fromWireValue resolves correctly`() {
        assertEquals(PromptRole.SYSTEM, PromptRole.fromWireValue("system"))
        assertEquals(PromptRole.USER, PromptRole.fromWireValue("user"))
        assertEquals(PromptRole.ASSISTANT, PromptRole.fromWireValue("assistant"))
        assertEquals(PromptRole.TOOL, PromptRole.fromWireValue("tool"))
    }
}
