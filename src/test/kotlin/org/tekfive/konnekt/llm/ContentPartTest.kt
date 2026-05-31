// src/test/kotlin/org/tekfive/konnekt/ai/ContentPartTest.kt
package org.tekfive.konnekt.llm

import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.llm.content.AudioSource
import org.tekfive.konnekt.llm.content.DocumentSource
import org.tekfive.konnekt.llm.content.ImageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContentPartTest {

    @Test
    fun `Text part holds text content`() {
        val part = LlmContentPart.Text("hello")
        assertEquals("hello", part.text)
    }

    @Test
    fun `Image part with Base64 source`() {
        val source = ImageSource.Base64("image/png", "iVBOR...")
        val part = LlmContentPart.Image(source)
        val src = assertIs<ImageSource.Base64>(part.source)
        assertEquals("image/png", src.mediaType)
        assertEquals("iVBOR...", src.data)
    }

    @Test
    fun `Image part with Url source`() {
        val source = ImageSource.Url("https://example.com/img.png")
        val part = LlmContentPart.Image(source)
        val src = assertIs<ImageSource.Url>(part.source)
        assertEquals("https://example.com/img.png", src.url)
    }

    @Test
    fun `Document part with Base64 source`() {
        val source = DocumentSource.Base64("application/pdf", "JVBERi0...")
        val part = LlmContentPart.Document(source)
        val src = assertIs<DocumentSource.Base64>(part.source)
        assertEquals("application/pdf", src.mediaType)
    }

    @Test
    fun `Document part with Url source`() {
        val source = DocumentSource.Url("https://example.com/doc.pdf")
        val part = LlmContentPart.Document(source)
        val src = assertIs<DocumentSource.Url>(part.source)
        assertEquals("https://example.com/doc.pdf", src.url)
    }

    @Test
    fun `Audio part with Base64 source`() {
        val source = AudioSource.Base64("audio/wav", "UklGR...")
        val part = LlmContentPart.Audio(source)
        val src = assertIs<AudioSource.Base64>(part.source)
        assertEquals("audio/wav", src.mediaType)
    }

    @Test
    fun `Audio part with Url source`() {
        val source = AudioSource.Url("https://example.com/audio.wav")
        val part = LlmContentPart.Audio(source)
        val src = assertIs<AudioSource.Url>(part.source)
        assertEquals("https://example.com/audio.wav", src.url)
    }

    @Test
    fun `ToolUse part holds id, name, and input`() {
        val input = JsonObject(mapOf("query" to "weather"))
        val part = LlmContentPart.ToolUse("call-1", "get_weather", input)
        assertEquals("call-1", part.id)
        assertEquals("get_weather", part.name)
        assertEquals("weather", part.input.reqString("query"))
    }

    @Test
    fun `ToolResult part holds toolUseId and content`() {
        val part = LlmContentPart.ToolResult("call-1", "72 degrees", false)
        assertEquals("call-1", part.toolUseId)
        assertEquals("72 degrees", part.content)
        assertFalse(part.isError)
        assertEquals(null, part.toolName)
    }

    @Test
    fun `ToolResult part can indicate error`() {
        val part = LlmContentPart.ToolResult("call-1", "Tool not found", true)
        assertTrue(part.isError)
    }

    @Test
    fun `ToolResult part can retain tool name`() {
        val part = LlmContentPart.ToolResult("call-1", "72 degrees", toolName = "get_weather")
        assertEquals("get_weather", part.toolName)
    }
}
