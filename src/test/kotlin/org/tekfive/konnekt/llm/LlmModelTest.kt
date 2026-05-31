package org.tekfive.konnekt.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlmModelTest {

    @Test
    fun `predefined models have expected values`() {
        assertEquals("claude-sonnet-4-20250514", LlmModel.CLAUDE_SONNET)
        assertEquals("claude-haiku-4-5-20251001", LlmModel.CLAUDE_HAIKU)
        assertEquals("gpt-4o", LlmModel.GPT_4O)
        assertEquals("gpt-4o-mini", LlmModel.GPT_4O_MINI)
        assertEquals("gemini-2.0-flash", LlmModel.GEMINI_2_FLASH)
        assertEquals("gemini-2.0-pro", LlmModel.GEMINI_2_PRO)
        assertEquals("grok-3", LlmModel.GROK_3)
        assertEquals("grok-3-mini", LlmModel.GROK_3_MINI)
        assertEquals("deepseek-chat", LlmModel.DEEPSEEK_V3)
        assertEquals("deepseek-reasoner", LlmModel.DEEPSEEK_R1)
        assertEquals("mistral-large-latest", LlmModel.MISTRAL_LARGE)
        assertEquals("mistral-small-latest", LlmModel.MISTRAL_SMALL)
    }

    @Test
    fun `predefined models resolve to correct providers`() {
        assertEquals(LlmServiceProviderType.ANTHROPIC, LlmServiceProviderType.forModel(LlmModel.CLAUDE_SONNET))
        assertEquals(LlmServiceProviderType.ANTHROPIC, LlmServiceProviderType.forModel(LlmModel.CLAUDE_HAIKU))
        assertEquals(LlmServiceProviderType.OPENAI, LlmServiceProviderType.forModel(LlmModel.GPT_4O))
        assertEquals(LlmServiceProviderType.OPENAI, LlmServiceProviderType.forModel(LlmModel.GPT_4O_MINI))
        assertEquals(LlmServiceProviderType.GOOGLE, LlmServiceProviderType.forModel(LlmModel.GEMINI_2_FLASH))
        assertEquals(LlmServiceProviderType.GOOGLE, LlmServiceProviderType.forModel(LlmModel.GEMINI_2_PRO))
        assertEquals(LlmServiceProviderType.GROK, LlmServiceProviderType.forModel(LlmModel.GROK_3))
        assertEquals(LlmServiceProviderType.GROK, LlmServiceProviderType.forModel(LlmModel.GROK_3_MINI))
        assertEquals(LlmServiceProviderType.DEEPSEEK, LlmServiceProviderType.forModel(LlmModel.DEEPSEEK_V3))
        assertEquals(LlmServiceProviderType.DEEPSEEK, LlmServiceProviderType.forModel(LlmModel.DEEPSEEK_R1))
        assertEquals(LlmServiceProviderType.MISTRAL, LlmServiceProviderType.forModel(LlmModel.MISTRAL_LARGE))
        assertEquals(LlmServiceProviderType.MISTRAL, LlmServiceProviderType.forModel(LlmModel.MISTRAL_SMALL))
    }

    @Test
    fun `forModel throws for unknown model prefix`() {
        assertFailsWith<IllegalArgumentException> {
            LlmServiceProviderType.forModel("unknown-model-id")
        }
    }
}
