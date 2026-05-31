package org.tekfive.konnekt.llm.content

import org.tekfive.jfk.schema.JsonSchema

data class Tool(
    val name: String,
    val description: String,
    val parameters: JsonSchema,
)

sealed class ToolChoice {
    data object Auto : ToolChoice()
    data object None : ToolChoice()
    data object Required : ToolChoice()
    data class Specific(val name: String) : ToolChoice()
}
