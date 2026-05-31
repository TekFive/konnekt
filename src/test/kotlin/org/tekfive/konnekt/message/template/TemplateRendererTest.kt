package org.tekfive.konnekt.message.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateRendererTest {

    private fun template(
        subject: String = "Subject",
        htmlBody: String = "<p>Body</p>",
        textBody: String = "Body",
        variables: List<TemplateVariableDeclaration> = emptyList(),
        validationIssues: List<TemplateValidationIssue> = emptyList(),
    ): MessageTemplate {
        return MessageTemplate(
            identifier = "test-template",
            name = "Test Template",
            subjectTemplate = subject,
            htmlBodyTemplate = htmlBody,
            textBodyTemplate = textBody,
            variables = variables,
            validationIssues = validationIssues,
        )
    }

    @Test
    fun `simple string substitution`() {
        val t = template(
            subject = "Hello {{name}}",
            htmlBody = "<p>Hello {{name}}</p>",
            textBody = "Hello {{name}}",
            variables = listOf(
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("name" to "Alice"))
        assertEquals("Hello Alice", result.subject)
        assertEquals("<p>Hello Alice</p>", result.htmlBody)
        assertEquals("Hello Alice", result.textBody)
    }

    @Test
    fun `HTML escaping in htmlBody but not in subject or textBody`() {
        val t = template(
            subject = "From {{name}}",
            htmlBody = "<p>From {{name}}</p>",
            textBody = "From {{name}}",
            variables = listOf(
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("name" to "<b>Bob & Co</b>"))
        assertEquals("From <b>Bob & Co</b>", result.subject)
        assertEquals("<p>From &lt;b&gt;Bob &amp; Co&lt;/b&gt;</p>", result.htmlBody)
        assertEquals("From <b>Bob & Co</b>", result.textBody)
    }

    @Test
    fun `missing optional variable renders as empty string`() {
        val t = template(
            subject = "Hello {{name}}{{title}}",
            htmlBody = "<p>{{title}}</p>",
            textBody = "{{title}}",
            variables = listOf(
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
                TemplateVariableDeclaration("title", TemplateVariableType.STRING, required = false),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("name" to "Alice"))
        assertEquals("Hello Alice", result.subject)
        assertEquals("<p></p>", result.htmlBody)
        assertEquals("", result.textBody)
    }

    @Test
    fun `missing required variable throws TemplateRenderException`() {
        val t = template(
            subject = "Hello {{name}}",
            variables = listOf(
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
            ),
        )
        val ex = assertFailsWith<TemplateRenderException> {
            TemplateRenderer.render(t, emptyMap())
        }
        assertTrue(ex.message!!.contains("name"))
    }

    @Test
    fun `sensitivity tags collected from present variables only`() {
        val t = template(
            subject = "{{name}} {{phone}}",
            variables = listOf(
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true, sensitivityTags = listOf("PII")),
                TemplateVariableDeclaration("phone", TemplateVariableType.STRING, required = false, sensitivityTags = listOf("PII", "CONTACT")),
                TemplateVariableDeclaration("ssn", TemplateVariableType.STRING, required = false, sensitivityTags = listOf("PHI")),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("name" to "Alice"))
        assertEquals(setOf("PII"), result.sensitivityTags)
    }

    @Test
    fun `template with stored validation issues that still hold throws TemplateValidationException`() {
        val t = template(
            subject = "Hello {{unknown}}",
            validationIssues = listOf(
                TemplateValidationIssue("unknown", "subject", "Undeclared variable: unknown"),
            ),
        )
        val ex = assertFailsWith<TemplateValidationException> {
            TemplateRenderer.render(t, emptyMap())
        }
        assertEquals(1, ex.issues.size)
    }

    @Test
    fun `template with stale validation issues that resolve renders and clears issues`() {
        val t = template(
            subject = "Hello {{name}}",
            htmlBody = "<p>Hello {{name}}</p>",
            textBody = "Hello {{name}}",
            variables = listOf(
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
            ),
            validationIssues = listOf(
                TemplateValidationIssue("oldVar", "subject", "Undeclared variable: oldVar"),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("name" to "Alice"))
        assertEquals("Hello Alice", result.subject)
        assertTrue(t.validationIssues.isEmpty(), "Expected stale issues to be cleared")
    }

    @Test
    fun `LIST variable renders as comma-separated`() {
        val t = template(
            subject = "Items: {{items}}",
            htmlBody = "<p>{{items}}</p>",
            textBody = "Items: {{items}}",
            variables = listOf(
                TemplateVariableDeclaration("items", TemplateVariableType.LIST, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("items" to listOf("apple", "banana", "cherry")))
        assertEquals("Items: apple, banana, cherry", result.subject)
        assertEquals("<p>apple, banana, cherry</p>", result.htmlBody)
        assertEquals("Items: apple, banana, cherry", result.textBody)
    }

    // --- Task 9: Conditionals ---

    @Test
    fun `if block included when variable is truthy`() {
        val t = template(
            subject = "Hello{{#if showTitle}} Dr.{{/if}} {{name}}",
            htmlBody = "<p>Hello{{#if showTitle}} Dr.{{/if}} {{name}}</p>",
            textBody = "Hello{{#if showTitle}} Dr.{{/if}} {{name}}",
            variables = listOf(
                TemplateVariableDeclaration("showTitle", TemplateVariableType.BOOLEAN, required = false),
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("showTitle" to true, "name" to "Smith"))
        assertEquals("Hello Dr. Smith", result.subject)
        assertEquals("<p>Hello Dr. Smith</p>", result.htmlBody)
        assertEquals("Hello Dr. Smith", result.textBody)
    }

    @Test
    fun `if block excluded when variable is falsy`() {
        val t = template(
            subject = "Hello{{#if showTitle}} Dr.{{/if}} {{name}}",
            htmlBody = "<p>Hello{{#if showTitle}} Dr.{{/if}} {{name}}</p>",
            textBody = "Hello{{#if showTitle}} Dr.{{/if}} {{name}}",
            variables = listOf(
                TemplateVariableDeclaration("showTitle", TemplateVariableType.BOOLEAN, required = false),
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("showTitle" to false, "name" to "Smith"))
        assertEquals("Hello Smith", result.subject)
    }

    @Test
    fun `if block excluded when variable is absent`() {
        val t = template(
            subject = "Hello{{#if showTitle}} Dr.{{/if}} {{name}}",
            variables = listOf(
                TemplateVariableDeclaration("showTitle", TemplateVariableType.BOOLEAN, required = false),
                TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("name" to "Smith"))
        assertEquals("Hello Smith", result.subject)
    }

    @Test
    fun `negated if block included when variable is falsy`() {
        val t = template(
            subject = "{{#if !premium}}Free tier{{/if}}",
            variables = listOf(
                TemplateVariableDeclaration("premium", TemplateVariableType.BOOLEAN, required = false),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("premium" to false))
        assertEquals("Free tier", result.subject)
    }

    @Test
    fun `negated if block excluded when variable is truthy`() {
        val t = template(
            subject = "{{#if !premium}}Free tier{{/if}}",
            variables = listOf(
                TemplateVariableDeclaration("premium", TemplateVariableType.BOOLEAN, required = false),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("premium" to true))
        assertEquals("", result.subject)
    }

    @Test
    fun `non-empty string is truthy, empty string is falsy`() {
        val t = template(
            subject = "{{#if note}}Has note{{/if}}{{#if empty}}Has empty{{/if}}",
            variables = listOf(
                TemplateVariableDeclaration("note", TemplateVariableType.STRING, required = false),
                TemplateVariableDeclaration("empty", TemplateVariableType.STRING, required = false),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("note" to "something", "empty" to ""))
        assertEquals("Has note", result.subject)
    }

    // --- Task 10: Loops ---

    @Test
    fun `each block repeats per item`() {
        val t = template(
            subject = "Items: {{#each items}}{{.}} {{/each}}",
            htmlBody = "<ul>{{#each items}}<li>{{.}}</li>{{/each}}</ul>",
            textBody = "{{#each items}}- {{.}}\n{{/each}}",
            variables = listOf(
                TemplateVariableDeclaration("items", TemplateVariableType.LIST, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("items" to listOf("apple", "banana", "cherry")))
        assertEquals("Items: apple banana cherry ", result.subject)
        assertEquals("<ul><li>apple</li><li>banana</li><li>cherry</li></ul>", result.htmlBody)
        assertEquals("- apple\n- banana\n- cherry\n", result.textBody)
    }

    @Test
    fun `empty list produces no output for each block`() {
        val t = template(
            subject = "Items:{{#each items}} {{.}}{{/each}}",
            variables = listOf(
                TemplateVariableDeclaration("items", TemplateVariableType.LIST, required = false),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("items" to emptyList<String>()))
        assertEquals("Items:", result.subject)
    }

    @Test
    fun `absent list produces no output for each block`() {
        val t = template(
            subject = "Items:{{#each items}} {{.}}{{/each}}",
            variables = listOf(
                TemplateVariableDeclaration("items", TemplateVariableType.LIST, required = false),
            ),
        )
        val result = TemplateRenderer.render(t, emptyMap())
        assertEquals("Items:", result.subject)
    }

    @Test
    fun `each block HTML-escapes items in htmlBody`() {
        val t = template(
            htmlBody = "<ul>{{#each items}}<li>{{.}}</li>{{/each}}</ul>",
            variables = listOf(
                TemplateVariableDeclaration("items", TemplateVariableType.LIST, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("items" to listOf("<b>bold</b>", "A & B")))
        assertEquals("<ul><li>&lt;b&gt;bold&lt;/b&gt;</li><li>A &amp; B</li></ul>", result.htmlBody)
    }

    @Test
    fun `render throws when variable type is incompatible`() {
        // Pass a String where NUMBER is declared
        val t = template(
            subject = "{{amount}}",
            htmlBody = "",
            textBody = "",
            variables = listOf(TemplateVariableDeclaration("amount", TemplateVariableType.NUMBER, required = true)),
        )
        assertFailsWith<TemplateRenderException> {
            TemplateRenderer.render(t, mapOf("amount" to "not a number"))
        }
    }

    @Test
    fun `each block raw items in textBody`() {
        val t = template(
            textBody = "{{#each items}}{{.}}\n{{/each}}",
            variables = listOf(
                TemplateVariableDeclaration("items", TemplateVariableType.LIST, required = true),
            ),
        )
        val result = TemplateRenderer.render(t, mapOf("items" to listOf("<b>bold</b>", "A & B")))
        assertEquals("<b>bold</b>\nA & B\n", result.textBody)
    }
}
