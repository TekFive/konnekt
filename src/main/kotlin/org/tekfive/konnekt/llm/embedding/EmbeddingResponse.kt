package org.tekfive.konnekt.llm.embedding

data class EmbeddingResponse(
    val embeddings: List<FloatArray>,
    val model: String,
    val inputTokens: Int? = null,
)
