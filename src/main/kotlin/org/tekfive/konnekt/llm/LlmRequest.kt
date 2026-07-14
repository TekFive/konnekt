package org.tekfive.konnekt.llm

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.JsonValue
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
            mergeBodyParameter(json, key, value)
        }
        return json
    }

    companion object {

        /**
         * Merges a caller-supplied extra body parameter into the built request body. When both the
         * existing and incoming values are objects they are merged recursively (the caller's leaf
         * values win); otherwise the caller's value replaces the built one. This lets provider-added
         * objects (e.g. vLLM's `chat_template_kwargs` for [LlmReasoningEffort.NONE]) coexist with
         * caller extras targeting the same object.
         */
        private fun mergeBodyParameter(target: JsonObject, key: String, value: JsonValue) {
            val existingObject = target[key].obj
            val valueObject = value.obj
            if (existingObject != null && valueObject != null) {
                valueObject.entries.forEach { (childKey, childValue) ->
                    mergeBodyParameter(existingObject, childKey, childValue)
                }
            } else {
                target[key] = value
            }
        }
    }
}
