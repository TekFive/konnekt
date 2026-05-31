package org.tekfive.konnekt.llm

import org.tekfive.konnekt.llm.embedding.EmbeddingRequest
import org.tekfive.konnekt.llm.embedding.EmbeddingResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmbeddingTypesTest {

    @Test
    fun `EmbeddingRequest holds input and model`() {
        val request = EmbeddingRequest(listOf("hello", "world"), "text-embedding-3-small")
        assertEquals(2, request.input.size)
        assertEquals("hello", request.input[0])
        assertEquals("text-embedding-3-small", request.model)
    }

    @Test
    fun `EmbeddingResponse holds embeddings and model`() {
        val embeddings = listOf(floatArrayOf(0.1f, 0.2f, 0.3f))
        val response = EmbeddingResponse(embeddings, "text-embedding-3-small", inputTokens = 5)
        assertEquals(1, response.embeddings.size)
        assertEquals(3, response.embeddings[0].size)
        assertEquals("text-embedding-3-small", response.model)
        assertEquals(5, response.inputTokens)
    }

    @Test
    fun `EmbeddingResponse inputTokens defaults to null`() {
        val response = EmbeddingResponse(emptyList(), "model")
        assertNull(response.inputTokens)
    }
}
