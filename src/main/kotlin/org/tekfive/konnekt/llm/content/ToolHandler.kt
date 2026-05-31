package org.tekfive.konnekt.llm.content

import org.tekfive.jfk.JsonObject

fun interface ToolHandler {
    fun execute(name: String, input: JsonObject): ToolHandlerResult
}

data class ToolHandlerResult(
    val content: String,
    val isError: Boolean = false,
)
