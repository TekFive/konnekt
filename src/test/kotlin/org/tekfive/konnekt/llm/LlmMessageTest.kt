package org.tekfive.konnekt.llm

import org.tekfive.konnekt.llm.content.ImageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LlmMessageTest {

    @Test
    fun `systemMessage creates message with SYSTEM role and text content`() {
        val msg = LlmMessage.systemMessage("You are a helpful assistant.")
        assertEquals(PromptRole.SYSTEM, msg.role)
        assertEquals(1, msg.content.size)
        val part = assertIs<LlmContentPart.Text>(msg.content[0])
        assertEquals("You are a helpful assistant.", part.text)
    }

    @Test
    fun `userMessage creates message with USER role and text content`() {
        val msg =  LlmMessage.userMessage("Hello!")
        assertEquals(PromptRole.USER, msg.role)
        assertEquals(1, msg.content.size)
        val part = assertIs<LlmContentPart.Text>(msg.content[0])
        assertEquals("Hello!", part.text)
    }

    @Test
    fun `assistantMessage creates message with ASSISTANT role and text content`() {
        val msg =   LlmMessage.assistantMessage("Hi there!")
        assertEquals(PromptRole.ASSISTANT, msg.role)
        val part = assertIs<LlmContentPart.Text>(msg.content[0])
        assertEquals("Hi there!", part.text)
    }

    @Test
    fun `userMessage with image creates multi-part content`() {
        val img = ImageSource.Base64("image/png", "iVBOR...")
        val msg =  LlmMessage.userMessage("Describe this image", img)
        assertEquals(PromptRole.USER, msg.role)
        assertEquals(2, msg.content.size)
        assertIs<LlmContentPart.Text>(msg.content[0])
        assertIs<LlmContentPart.Image>(msg.content[1])
    }

    @Test
    fun `toolResultMessage creates message with TOOL role`() {
        val msg = LlmMessage.toolResultMessage("call-1", "72 degrees")
        assertEquals(PromptRole.TOOL, msg.role)
        assertEquals(1, msg.content.size)
        val part = assertIs<LlmContentPart.ToolResult>(msg.content[0])
        assertEquals("call-1", part.toolUseId)
        assertEquals("72 degrees", part.content)
    }

    @Test
    fun `toolResultMessage with error`() {
        val msg = LlmMessage.toolResultMessage("call-1", "Failed", isError = true)
        val part = assertIs<LlmContentPart.ToolResult>(msg.content[0])
        assert(part.isError)
    }

    @Test
    fun `toolResultMessage can include tool name`() {
        val msg = LlmMessage.toolResultMessage("call-1", "72 degrees", toolName = "get_weather")
        val part = assertIs<LlmContentPart.ToolResult>(msg.content[0])
        assertEquals("get_weather", part.toolName)
    }

    @Test
    fun `text convenience property concatenates text parts`() {
        val msg = LlmMessage(PromptRole.USER, listOf(
            LlmContentPart.Text("Hello "),
            LlmContentPart.Image(ImageSource.Url("https://example.com/img.png")),
            LlmContentPart.Text("world"),
        ))
        assertEquals("Hello world", msg.text)
    }

    @Test
    fun `text property returns empty string when no text parts`() {
        val msg = LlmMessage(PromptRole.USER, listOf(
            LlmContentPart.Image(ImageSource.Url("https://example.com/img.png")),
        ))
        assertEquals("", msg.text)
    }

    @Test
    fun `Message is a data class with equality`() {
        val a =  LlmMessage.userMessage("hello")
        val b =  LlmMessage.userMessage("hello")
        assertEquals(a, b)
    }
}
