package org.tekfive.konnekt.llm

enum class PromptRole(val wireValue: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    ;

    companion object {
        fun fromWireValue(value: String): PromptRole {
            return entries.first { it.wireValue == value }
        }
    }
}
