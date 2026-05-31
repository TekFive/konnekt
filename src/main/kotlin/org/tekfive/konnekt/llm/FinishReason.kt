package org.tekfive.konnekt.llm

enum class FinishReason(val wireValue: String) {
    STOP("stop"),
    MAX_TOKENS("max_tokens"),
    TOOL_USE("tool_use"),
    CONTENT_FILTER("content_filter"),
    ;

    companion object {
        fun fromWireValue(value: String): FinishReason {
            return entries.firstOrNull { it.wireValue == value } ?: STOP
        }

        fun fromWireValueOrNull(value: String?): FinishReason? {
            if (value == null) return null
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}
