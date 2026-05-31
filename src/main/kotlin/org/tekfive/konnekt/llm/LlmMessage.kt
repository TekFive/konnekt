package org.tekfive.konnekt.llm

import org.tekfive.konnekt.llm.content.ImageSource

data class LlmMessage(
    val role: PromptRole,
    val content: List<LlmContentPart>,
) {
    val text: String
        get() = content.filterIsInstance<LlmContentPart.Text>().joinToString("") { it.text }

    companion object {
        fun systemMessage(content: String) = LlmMessage(PromptRole.SYSTEM, listOf(LlmContentPart.Text(content)))

        fun userMessage(content: String) = LlmMessage(PromptRole.USER, listOf(LlmContentPart.Text(content)))

        fun userMessage(content: String, image: ImageSource) = LlmMessage(
            PromptRole.USER,
            listOf(LlmContentPart.Text(content), LlmContentPart.Image(image)),
        )

        fun assistantMessage(content: String) = LlmMessage(PromptRole.ASSISTANT, listOf(LlmContentPart.Text(content)))

        fun toolResultMessage(
            toolUseId: String,
            content: String,
            isError: Boolean = false,
            toolName: String? = null,
        ) = LlmMessage(
            PromptRole.TOOL,
            listOf(LlmContentPart.ToolResult(toolUseId, content, isError, toolName)),
        )
    }
}
