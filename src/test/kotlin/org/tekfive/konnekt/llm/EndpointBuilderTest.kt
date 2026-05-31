package org.tekfive.konnekt.llm

import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndpointBuilderTest {

    private fun withConfig(properties: Map<String, String>, block: () -> Unit) {
        AckRegistry.addSource(MapSource(properties))
        try {
            block()
        } finally {
            AckRegistry.clear()
        }
    }

    @Test
    fun `builds endpoints for configured providers`() {
        withConfig(mapOf(
            "KONNEKT_ANTHROPIC_API_KEY" to "sk-ant-test",
            "KONNEKT_OPENAI_API_KEY" to "sk-openai-test",
        )) {
            val endpoints = EndpointBuilder(LlmServiceProviderType.ANTHROPIC, LlmServiceProviderType.OPENAI).build()
            assertEquals(2, endpoints.size)
            assertEquals(LlmServiceProviderType.ANTHROPIC, endpoints[0].providerType)
            assertEquals("sk-ant-test", endpoints[0].apiKey)
            assertEquals(LlmServiceProviderType.OPENAI, endpoints[1].providerType)
            assertEquals("sk-openai-test", endpoints[1].apiKey)
        }
    }

    @Test
    fun `skips providers without API key`() {
        withConfig(mapOf(
            "KONNEKT_ANTHROPIC_API_KEY" to "sk-ant-test",
        )) {
            val endpoints = EndpointBuilder(LlmServiceProviderType.ANTHROPIC, LlmServiceProviderType.OPENAI).build()
            assertEquals(1, endpoints.size)
            assertEquals(LlmServiceProviderType.ANTHROPIC, endpoints[0].providerType)
        }
    }

    @Test
    fun `includes model when configured`() {
        withConfig(mapOf(
            "KONNEKT_ANTHROPIC_API_KEY" to "sk-ant-test",
            "KONNEKT_ANTHROPIC_MODEL" to "claude-sonnet-4-20250514",
        )) {
            val endpoints = EndpointBuilder(LlmServiceProviderType.ANTHROPIC).build()
            assertEquals(1, endpoints.size)
            assertEquals("claude-sonnet-4-20250514", endpoints[0].model)
        }
    }

    @Test
    fun `includes baseUrl when configured`() {
        withConfig(mapOf(
            "KONNEKT_OPENAI_API_KEY" to "sk-test",
            "KONNEKT_OPENAI_BASE_URL" to "https://custom.openai.com",
        )) {
            val endpoints = EndpointBuilder(LlmServiceProviderType.OPENAI).build()
            assertEquals(1, endpoints.size)
            assertEquals("https://custom.openai.com", endpoints[0].baseUrl)
        }
    }

    @Test
    fun `returns empty list when no providers configured`() {
        withConfig(emptyMap()) {
            val endpoints = EndpointBuilder(LlmServiceProviderType.ANTHROPIC, LlmServiceProviderType.OPENAI).build()
            assertTrue(endpoints.isEmpty())
        }
    }

    @Test
    fun `preserves provider order`() {
        withConfig(mapOf(
            "KONNEKT_GROK_API_KEY" to "xai-test",
            "KONNEKT_ANTHROPIC_API_KEY" to "sk-ant-test",
            "KONNEKT_OPENAI_API_KEY" to "sk-openai-test",
        )) {
            val endpoints = EndpointBuilder(LlmServiceProviderType.GROK, LlmServiceProviderType.ANTHROPIC, LlmServiceProviderType.OPENAI).build()
            assertEquals(3, endpoints.size)
            assertEquals(LlmServiceProviderType.GROK, endpoints[0].providerType)
            assertEquals(LlmServiceProviderType.ANTHROPIC, endpoints[1].providerType)
            assertEquals(LlmServiceProviderType.OPENAI, endpoints[2].providerType)
        }
    }
}
