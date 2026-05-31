package org.tekfive.konnekt.llm

import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import org.tekfive.konnekt.llm.content.ToolHandler
import org.tekfive.konnekt.llm.content.ToolHandlerResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ToolTest {

    @Test
    fun `Tool holds name, description, and parameters`() {
        val params = objectSchema {
            properties {
                "query" to stringSchema { description = "Search query" }
            }
            required("query")
        }
        val tool = Tool("search", "Search the web", params)
        assertEquals("search", tool.name)
        assertEquals("Search the web", tool.description)
        assertEquals(1, tool.parameters.properties!!.size)
    }

    @Test
    fun `ToolChoice Auto`() {
        val choice = ToolChoice.Auto
        assertIs<ToolChoice.Auto>(choice)
    }

    @Test
    fun `ToolChoice None`() {
        val choice = ToolChoice.None
        assertIs<ToolChoice.None>(choice)
    }

    @Test
    fun `ToolChoice Required`() {
        val choice = ToolChoice.Required
        assertIs<ToolChoice.Required>(choice)
    }

    @Test
    fun `ToolChoice Specific`() {
        val choice = ToolChoice.Specific("get_weather")
        assertEquals("get_weather", choice.name)
    }

    @Test
    fun `ToolHandlerResult defaults to not error`() {
        val result = ToolHandlerResult("72 degrees")
        assertEquals("72 degrees", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun `ToolHandlerResult with error`() {
        val result = ToolHandlerResult("Tool failed", isError = true)
        assertEquals("Tool failed", result.content)
        assert(result.isError)
    }

    @Test
    fun `ToolHandler functional interface`() {
        val handler = ToolHandler { name, input ->
            ToolHandlerResult("result for $name")
        }
        val result = handler.execute("test", org.tekfive.jfk.JsonObject())
        assertEquals("result for test", result.content)
    }
}
