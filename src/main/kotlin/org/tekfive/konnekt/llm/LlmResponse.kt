package org.tekfive.konnekt.llm

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asJsonObject
import org.tekfive.jfk.asRequiredJsonObject

data class LlmResponse(
    val contentParts: List<LlmContentPart>,
    val model: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val finishReason: FinishReason? = null,
    val endpoint: LlmEndpoint? = null,
    val rateLimits: RateLimits? = null,
    val reasoning: String? = null,
) {
    val content: String
        get() = contentParts.filterIsInstance<LlmContentPart.Text>().joinToString("") { it.text }

    val totalTokens: Int?
        get() {
            val input = inputTokens ?: return null
            val output = outputTokens ?: return null
            return input + output
        }

    val toolUses: List<LlmContentPart.ToolUse>
        get() = contentParts.filterIsInstance<LlmContentPart.ToolUse>()

    val hasToolUse: Boolean
        get() = toolUses.isNotEmpty()

    fun contentAsJson(): JsonObject? {
        return content.asJsonObject()
    }

    companion object {
        fun fromText(
            text: String,
            model: String,
            inputTokens: Int? = null,
            outputTokens: Int? = null,
            finishReason: FinishReason? = null,
            endpoint: LlmEndpoint? = null,
            rateLimits: RateLimits? = null,
            reasoning: String? = null,
        ): LlmResponse {
            return LlmResponse(
                contentParts = listOf(LlmContentPart.Text(text)),
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                finishReason = finishReason,
                endpoint = endpoint,
                rateLimits = rateLimits,
                reasoning = reasoning,
            )
        }
    }
}
