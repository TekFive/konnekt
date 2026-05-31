package org.tekfive.konnekt.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EndpointTest {

    @Test
    fun `endpoint with all fields`() {
        val endpoint = LlmEndpoint(
            providerType = LlmServiceProviderType.OPENAI,
            model = "gpt-4o",
            apiKey = "sk-test",
            baseUrl = "https://custom.openai.com",
        )
        assertEquals(LlmServiceProviderType.OPENAI, endpoint.providerType)
        assertEquals("gpt-4o", endpoint.model)
        assertEquals("sk-test", endpoint.apiKey)
        assertEquals("https://custom.openai.com", endpoint.baseUrl)
    }

    @Test
    fun `endpoint with minimal fields`() {
        val endpoint = LlmEndpoint(providerType = LlmServiceProviderType.ANTHROPIC)
        assertEquals(LlmServiceProviderType.ANTHROPIC, endpoint.providerType)
        assertNull(endpoint.model)
        assertNull(endpoint.apiKey)
        assertNull(endpoint.baseUrl)
    }

    @Test
    fun `resolvedBaseUrl returns endpoint baseUrl when set`() {
        val endpoint = LlmEndpoint(
            providerType = LlmServiceProviderType.OPENAI,
            baseUrl = "https://custom.openai.com",
        )
        assertEquals("https://custom.openai.com", endpoint.resolvedBaseUrl)
    }

    @Test
    fun `resolvedBaseUrl falls back to provider default`() {
        val endpoint = LlmEndpoint(providerType = LlmServiceProviderType.OPENAI)
        assertEquals("https://api.openai.com", endpoint.resolvedBaseUrl)
    }

    @Test
    fun `endpoints with same fields are equal`() {
        val a = LlmEndpoint(LlmServiceProviderType.OPENAI, "gpt-4o", "sk-test")
        val b = LlmEndpoint(LlmServiceProviderType.OPENAI, "gpt-4o", "sk-test")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
