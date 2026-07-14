package org.tekfive.konnekt.llm

/**
 * Reasoning effort for a request. A null effort on [LlmRequest] means the provider default;
 * [NONE] explicitly disables reasoning, which each provider maps to its own disable mechanism.
 */
enum class LlmReasoningEffort(val displayName: String) {
    NONE("None"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}
