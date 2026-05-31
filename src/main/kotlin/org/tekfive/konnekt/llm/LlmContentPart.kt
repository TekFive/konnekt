package org.tekfive.konnekt.llm

import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.llm.content.AudioSource
import org.tekfive.konnekt.llm.content.DocumentSource
import org.tekfive.konnekt.llm.content.ImageSource

sealed class LlmContentPart {
    data class Text(val text: String) : LlmContentPart()
    data class Image(val source: ImageSource) : LlmContentPart()
    data class Document(val source: DocumentSource) : LlmContentPart()
    data class Audio(val source: AudioSource) : LlmContentPart()
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : LlmContentPart()
    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
        val toolName: String? = null,
    ) : LlmContentPart()
}
