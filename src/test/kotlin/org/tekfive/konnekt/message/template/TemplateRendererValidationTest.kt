package org.tekfive.konnekt.message.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateRendererValidationTest {

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
    fun `valid template returns empty issues`() {
        val t = template(
            subject = "Hello {{name}}",
            htmlBody = "<p>Hello {{name}}</p>",
            textBody = "Hello {{name}}",
            variables = listOf(
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertTrue(issues.isEmpty(), "Expected no validation issues but got: $issues")
    }

    @Test
    fun `undeclared placeholder in subject`() {
        val t = template(subject = "Hello {{unknown}}")
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertEquals("{{unknown}}", issues[0].placeholder)
        assertEquals("subject", issues[0].location)
        assertTrue(issues[0].message.contains("undeclared", ignoreCase = true))
    }

    @Test
    fun `undeclared placeholder in htmlBody`() {
        val t = template(htmlBody = "<p>{{missing}}</p>")
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertEquals("{{missing}}", issues[0].placeholder)
        assertEquals("htmlBody", issues[0].location)
    }

    @Test
    fun `undeclared placeholder in textBody`() {
        val t = template(textBody = "Value: {{absent}}")
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertEquals("{{absent}}", issues[0].placeholder)
        assertEquals("textBody", issues[0].location)
    }

    @Test
    fun `invalid DecimalFormat pattern`() {
        val t = template(
            subject = "{{amount|###..##}}",
            variables = listOf(
                TemplateVariableDeclaration("amount", TemplateVariableType.NUMBER, required = true),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("format", ignoreCase = true))
    }

    @Test
    fun `invalid DateTimeFormatter pattern`() {
        val t = template(
            subject = "{{date|QQQQQQQ}}",
            variables = listOf(
                TemplateVariableDeclaration("date", TemplateVariableType.TEMPORAL, required = true),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("format", ignoreCase = true))
    }

    @Test
    fun `invalid boolean format missing separator`() {
        val t = template(
            subject = "{{flag|TrueFalse}}",
            variables = listOf(
                TemplateVariableDeclaration("flag", TemplateVariableType.BOOLEAN, required = true),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("/", ignoreCase = true))
    }

    @Test
    fun `format specifier on STRING produces issue`() {
        val t = template(
            subject = "{{name|uppercase}}",
            variables = listOf(
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("format", ignoreCase = true))
    }

    @Test
    fun `format specifier on LIST produces issue`() {
        val t = template(
            subject = "{{items|csv}}",
            variables = listOf(
                TemplateVariableDeclaration("items", TemplateVariableType.LIST, required = true),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("format", ignoreCase = true))
    }

    @Test
    fun `valid format specifiers accepted`() {
        val t = template(
            subject = "{{amount|#,##0.00}} {{date|yyyy-MM-dd}} {{flag|Yes/No}}",
            variables = listOf(
                TemplateVariableDeclaration("amount", TemplateVariableType.NUMBER, required = true),
                TemplateVariableDeclaration("date", TemplateVariableType.TEMPORAL, required = true),
                TemplateVariableDeclaration("flag", TemplateVariableType.BOOLEAN, required = true),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertTrue(issues.isEmpty(), "Expected no issues but got: $issues")
    }

    @Test
    fun `block tags are ignored`() {
        val t = template(
            htmlBody = "{{#if showName}}<p>{{name}}</p>{{/if}}{{#each items}}{{.}}{{/each}}",
            variables = listOf(
                TemplateVariableDeclaration("showName", TemplateVariableType.BOOLEAN, required = false),
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
                TemplateVariableDeclaration("items", TemplateVariableType.LIST, required = false),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertTrue(issues.isEmpty(), "Expected no issues but got: $issues")
    }

    @Test
    fun `same variable with different formats in different fields`() {
        val t = template(
            subject = "{{amount|#,##0}}",
            htmlBody = "<p>{{amount|#,##0.00}}</p>",
            textBody = "{{amount}}",
            variables = listOf(
                TemplateVariableDeclaration("amount", TemplateVariableType.NUMBER, required = true),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertTrue(issues.isEmpty(), "Expected no issues but got: $issues")
    }

    @Test
    fun `negated if block tags are ignored`() {
        val t = template(
            htmlBody = "{{#if !hidden}}<p>Visible</p>{{/if}}",
            variables = listOf(
                TemplateVariableDeclaration("hidden", TemplateVariableType.BOOLEAN, required = false),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertTrue(issues.isEmpty(), "Expected no issues but got: $issues")
    }

    @Test
    fun `whitespace inside block tags is tolerated`() {
        val t = template(
            htmlBody = "{{#if show }}<p>{{name}}</p>{{/if }}{{ #each items }}{{.}}{{ /each }}",
            variables = listOf(
                TemplateVariableDeclaration("show", TemplateVariableType.BOOLEAN, required = false),
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
                TemplateVariableDeclaration("items", TemplateVariableType.LIST, required = false),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertTrue(issues.isEmpty(), "Expected no issues but got: $issues")
    }

    @Test
    fun `unclosed if block produces issue`() {
        val t = template(
            subject = "{{#if flag}}text",
            variables = listOf(
                TemplateVariableDeclaration("flag", TemplateVariableType.BOOLEAN, required = false),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertEquals("subject", issues[0].location)
        assertTrue(issues[0].message.contains("unclosed", ignoreCase = true))
    }

    @Test
    fun `unclosed each block produces issue`() {
        val t = template(
            subject = "{{#each items}}{{.}}",
            variables = listOf(
                TemplateVariableDeclaration("items", TemplateVariableType.LIST, required = false),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertTrue(issues.any { it.message.contains("unclosed", ignoreCase = true) }, "Expected an unclosed-block issue but got: $issues")
    }

    @Test
    fun `orphaned closing tag produces issue`() {
        val t = template(subject = "text{{/if}}")
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("orphaned", ignoreCase = true))
    }

    @Test
    fun `nested if blocks produce issue`() {
        val t = template(
            subject = "{{#if a}}{{#if b}}x{{/if}}{{/if}}",
            variables = listOf(
                TemplateVariableDeclaration("a", TemplateVariableType.BOOLEAN, required = false),
                TemplateVariableDeclaration("b", TemplateVariableType.BOOLEAN, required = false),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("not supported", ignoreCase = true))
    }

    @Test
    fun `nested each blocks produce issue`() {
        val t = template(
            subject = "{{#each outer}}{{#each inner}}{{.}}{{/each}}{{/each}}",
            variables = listOf(
                TemplateVariableDeclaration("outer", TemplateVariableType.LIST, required = false),
                TemplateVariableDeclaration("inner", TemplateVariableType.LIST, required = false),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertTrue(issues.any { it.message.contains("not supported", ignoreCase = true) }, "Expected a nested-block issue but got: $issues")
    }

    @Test
    fun `undeclared variable in each block tag produces issue`() {
        val t = template(
            subject = "{{#each reslts}}{{.}}{{/each}}",
            variables = listOf(
                TemplateVariableDeclaration("results", TemplateVariableType.LIST, required = false),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("undeclared", ignoreCase = true))
        assertTrue(issues[0].message.contains("reslts"))
    }

    @Test
    fun `undeclared variable in if block tag produces issue`() {
        val t = template(subject = "{{#if flg}}x{{/if}}")
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("undeclared", ignoreCase = true))
        assertTrue(issues[0].message.contains("flg"))
    }

    @Test
    fun `duplicate variable declaration produces issue`() {
        val t = template(
            subject = "Hello {{name}}",
            variables = listOf(
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = false),
            ),
        )
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertEquals("variables", issues[0].location)
        assertTrue(issues[0].message.contains("duplicate", ignoreCase = true))
    }

    @Test
    fun `dot placeholder outside each block produces issue`() {
        val t = template(subject = "Value: {{.}}")
        val issues = TemplateRenderer.validate(t)
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("{{#each}}"))
    }
}
