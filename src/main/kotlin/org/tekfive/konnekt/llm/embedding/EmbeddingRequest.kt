package org.tekfive.konnekt.llm.embedding

data class EmbeddingRequest(
    val input: List<String>,
    val model: String,
)
