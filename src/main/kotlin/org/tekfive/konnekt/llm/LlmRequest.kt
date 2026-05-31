package org.tekfive.konnekt.llm

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.schema.JsonSchema
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice

data class LlmRequest(
    val messages: List<LlmMessage>,
    val endpoint: LlmEndpoint,
    val fallbackEndpoints: List<LlmEndpoint> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val stopSequences: List<String>? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val responseSchema: JsonSchema? = null,
    val tools: List<Tool>? = null,
    val toolChoice: ToolChoice? = null,
    val extraBodyParameters: JsonObject? = null,
    val reasoningEffort: LlmReasoningEffort? = null,
    val shouldFallback: ((LlmException) -> Boolean)? = null,
) {
    init {
        require(messages.isNotEmpty()) { "Messages must not be empty" }
        temperature?.let { require(it in 0.0..2.0) { "Temperature must be between 0.0 and 2.0" } }
        maxTokens?.let { require(it > 0) { "Max tokens must be positive" } }
    }

    internal fun applyExtraBodyParameters(json: JsonObject): JsonObject {
       extraBodyParameters?.entries?.forEach { (key, value) ->
            json[key] = value
        }
        return json
    }
}
