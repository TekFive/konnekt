package org.tekfive.konnekt.message.template

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateRendererFormatTest {

    private fun template(
        subject: String = "Subject",
        htmlBody: String = "<p>Body</p>",
        textBody: String = "Body",
        variables: List<TemplateVariableDeclaration> = emptyList(),
    ): MessageTemplate {
        return MessageTemplate(
            identifier = "test-template",
            name = "Test Template",
            subjectTemplate = subject,
            htmlBodyTemplate = htmlBody,
            textBodyTemplate = textBody,
            variables = variables,
        )
    }

    @Test
    fun `NUMBER with DecimalFormat pattern`() {
        val t = template(
            subject = "Total: {{amount|#,##0.00}}",
            htmlBody = "<p>Total: {{amount|#,##0.00}}</p>",
            textBody = "Total: {{amount|#,##0.00}}",
            variables = listOf(
                TemplateVariableDeclaration("amount", TemplateVariableType.NUMBER, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("amount" to 1234567.89))
        assertEquals("Total: 1,234,567.89", result.subject)
        assertEquals("<p>Total: 1,234,567.89</p>", result.htmlBody)
        assertEquals("Total: 1,234,567.89", result.textBody)
    }

    @Test
    fun `TEMPORAL with DateTimeFormatter pattern for LocalDate`() {
        val t = template(
            subject = "Date: {{date|yyyy-MM-dd}}",
            variables = listOf(
                TemplateVariableDeclaration("date", TemplateVariableType.TEMPORAL, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("date" to LocalDate.of(2026, 4, 11)))
        assertEquals("Date: 2026-04-11", result.subject)
    }

    @Test
    fun `TEMPORAL with DateTimeFormatter pattern for LocalDateTime`() {
        val t = template(
            subject = "At: {{timestamp|MMM d, yyyy h:mm a}}",
            variables = listOf(
                TemplateVariableDeclaration("timestamp", TemplateVariableType.TEMPORAL, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("timestamp" to LocalDateTime.of(2026, 4, 11, 14, 30)))
        assertEquals("At: Apr 11, 2026 2:30 PM", result.subject)
    }

    @Test
    fun `TEMPORAL with DateTimeFormatter pattern for ZonedDateTime`() {
        val t = template(
            subject = "At: {{timestamp|yyyy-MM-dd HH:mm z}}",
            variables = listOf(
                TemplateVariableDeclaration("timestamp", TemplateVariableType.TEMPORAL, required = true),
            ),
        )
        val zdt = ZonedDateTime.of(2026, 4, 11, 14, 30, 0, 0, ZoneId.of("America/New_York"))
        val result = TemplateRenderer.render(t, mapOf("timestamp" to zdt))
        assertEquals("At: 2026-04-11 14:30 EDT", result.subject)
    }

    @Test
    fun `BOOLEAN with true and false labels`() {
        val t = template(
            subject = "Active: {{active|Yes/No}}",
            variables = listOf(
                TemplateVariableDeclaration("active", TemplateVariableType.BOOLEAN, required = true),
            ),
        )
        val trueResult = TemplateRenderer.render(t, mapOf("active" to true))
        assertEquals("Active: Yes", trueResult.subject)

        val falseResult = TemplateRenderer.render(t, mapOf("active" to false))
        assertEquals("Active: No", falseResult.subject)
    }

    @Test
    fun `same variable different format specifiers in different fields`() {
        val t = template(
            subject = "{{amount|#,##0}}",
            htmlBody = "<p>{{amount|#,##0.00}}</p>",
            textBody = "{{amount}}",
            variables = listOf(
                TemplateVariableDeclaration("amount", TemplateVariableType.NUMBER, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("amount" to 1234.567))
        assertEquals("1,235", result.subject)
        assertEquals("<p>1,234.57</p>", result.htmlBody)
        assertEquals("1234.567", result.textBody)
    }

    @Test
    fun `HTML escaping of formatted output`() {
        val t = template(
            htmlBody = "<p>{{active|<Yes>/<No>}}</p>",
            variables = listOf(
                TemplateVariableDeclaration("active", TemplateVariableType.BOOLEAN, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("active" to true))
        assertEquals("<p>&lt;Yes&gt;</p>", result.htmlBody)
    }

    @Test
    fun `NUMBER without format uses toString`() {
        val t = template(
            subject = "Count: {{count}}",
            variables = listOf(
                TemplateVariableDeclaration("count", TemplateVariableType.NUMBER, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("count" to 42))
        assertEquals("Count: 42", result.subject)
    }

    @Test
    fun `mismatched temporal pattern throws TemplateRenderException naming variable and pattern only`() {
        val t = template(
            subject = "Time: {{date|HH:mm}}",
            variables = listOf(
                TemplateVariableDeclaration("date", TemplateVariableType.TEMPORAL, required = true),
            ),
        )
        val ex = assertFailsWith<TemplateRenderException> {
            TemplateRenderer.render(t, mapOf("date" to LocalDate.of(2026, 4, 11)))
        }
        assertTrue(ex.message!!.contains("date"))
        assertTrue(ex.message!!.contains("HH:mm"))
        assertFalse(ex.message!!.contains("2026"), "Exception message must never contain the value")
    }

    @Test
    fun `TEMPORAL without format uses toString`() {
        val t = template(
            subject = "Date: {{date}}",
            variables = listOf(
                TemplateVariableDeclaration("date", TemplateVariableType.TEMPORAL, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("date" to LocalDate.of(2026, 4, 11)))
        assertEquals("Date: 2026-04-11", result.subject)
    }
}
