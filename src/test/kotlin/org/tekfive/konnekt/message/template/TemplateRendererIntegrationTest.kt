package org.tekfive.konnekt.message.template

import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateRendererIntegrationTest {

    @Test
    fun `full pipeline renders template with conditionals, loops, formatting, and sensitivity tags`() {
        val template = MessageTemplate(
            identifier = "abnormal-lab-alert",
            name = "Abnormal Lab Alert",
            subjectTemplate = "Lab Results for {{patientName}} — {{resultDate|MM/dd/yyyy}}",
            htmlBodyTemplate = """
                <h1>Lab Results for {{patientName}}</h1>
                <p>Date: {{resultDate|MMMM d, yyyy}}</p>
                <p>Total charges: {{totalCharges|#,##0.00}}</p>
                {{#if hasAbnormal}}
                <p><strong>Abnormal results detected.</strong></p>
                {{/if}}
                {{#if results}}
                <ul>
                {{#each results}}
                <li>{{.}}</li>
                {{/each}}
                </ul>
                {{/if}}
                <p>Follow-up required: {{followUpRequired|Yes/No}}</p>
            """.trimIndent(),
            textBodyTemplate = """
                Lab Results for {{patientName}}
                Date: {{resultDate|MM/dd/yyyy}}
                Total charges: {{totalCharges|#,##0.00}}
                {{#if hasAbnormal}}
                ABNORMAL RESULTS DETECTED
                {{/if}}
                {{#each results}}
                - {{.}}
                {{/each}}
                Follow-up required: {{followUpRequired|Yes/No}}
            """.trimIndent(),
            variables = listOf(
                TemplateVariableDeclaration("patientName", TemplateVariableType.STRING, required = true, sensitivityTags = listOf("IDENTITY")),
                TemplateVariableDeclaration("resultDate", TemplateVariableType.TEMPORAL, required = true),
                TemplateVariableDeclaration("totalCharges", TemplateVariableType.NUMBER, required = false, sensitivityTags = listOf("FINANCIAL")),
                TemplateVariableDeclaration("hasAbnormal", TemplateVariableType.BOOLEAN, required = false, sensitivityTags = listOf("CLINICAL")),
                TemplateVariableDeclaration("results", TemplateVariableType.LIST, required = false, sensitivityTags = listOf("CLINICAL")),
                TemplateVariableDeclaration("followUpRequired", TemplateVariableType.BOOLEAN, required = true),
            ),
        )

        val variables = mapOf<String, Any>(
            "patientName" to "Jane Doe",
            "resultDate" to LocalDate.of(2026, 4, 11),
            "totalCharges" to 1234.5,
            "hasAbnormal" to true,
            "results" to listOf("Hemoglobin: 8.2 g/dL", "Potassium: 6.1 mEq/L"),
            "followUpRequired" to true,
        )

        val result = TemplateRenderer.render(template, variables)

        // Subject uses format specifier
        assertEquals("Lab Results for Jane Doe — 04/11/2026", result.subject)

        // HTML body: formatted values, conditional included, loop expanded
        assertTrue(result.htmlBody.contains("<h1>Lab Results for Jane Doe</h1>"))
        assertTrue(result.htmlBody.contains("April 11, 2026"))
        assertTrue(result.htmlBody.contains("1,234.50"))
        assertTrue(result.htmlBody.contains("<strong>Abnormal results detected.</strong>"))
        assertTrue(result.htmlBody.contains("<li>Hemoglobin: 8.2 g/dL</li>"))
        assertTrue(result.htmlBody.contains("<li>Potassium: 6.1 mEq/L</li>"))
        assertTrue(result.htmlBody.contains("Follow-up required: Yes"))

        // Text body: same logic, raw values
        assertTrue(result.textBody.contains("Jane Doe"))
        assertTrue(result.textBody.contains("04/11/2026"))
        assertTrue(result.textBody.contains("1,234.50"))
        assertTrue(result.textBody.contains("ABNORMAL RESULTS DETECTED"))
        assertTrue(result.textBody.contains("- Hemoglobin: 8.2 g/dL"))
        assertTrue(result.textBody.contains("Follow-up required: Yes"))

        // Sensitivity tags from all present variables
        assertEquals(setOf("IDENTITY", "FINANCIAL", "CLINICAL"), result.sensitivityTags)

        // Convenience method produces EmailMessage
        val email = result.toEmailMessage(
            to = listOf(MessageRecipient("doctor@clinic.com")),
            from = MessageAddress("noreply@clinic.com"),
        )
        assertEquals("Lab Results for Jane Doe — 04/11/2026", email.subject)
        assertEquals(result.htmlBody, email.body)
    }

    @Test
    fun `if guard around each block suppresses list markup when results are empty`() {
        val template = MessageTemplate(
            identifier = "results-guard",
            name = "Results Guard",
            subjectTemplate = "Results for {{patientName}}",
            htmlBodyTemplate = "{{#if results}}<ul>{{#each results}}<li>{{.}}</li>{{/each}}</ul>{{/if}}",
            textBodyTemplate = "{{#if results}}Results:{{#each results}} {{.}}{{/each}}{{/if}}",
            variables = listOf(
                TemplateVariableDeclaration("patientName", TemplateVariableType.STRING, required = true),
                TemplateVariableDeclaration("results", TemplateVariableType.LIST, required = false),
            ),
        )

        val empty = TemplateRenderer.render(
            template,
            mapOf("patientName" to "Jane Doe", "results" to emptyList<String>()),
        )
        assertEquals("", empty.htmlBody)
        assertEquals("", empty.textBody)

        val populated = TemplateRenderer.render(
            template,
            mapOf("patientName" to "Jane Doe", "results" to listOf("Hemoglobin: 8.2 g/dL")),
        )
        assertEquals("<ul><li>Hemoglobin: 8.2 g/dL</li></ul>", populated.htmlBody)
        assertEquals("Results: Hemoglobin: 8.2 g/dL", populated.textBody)
    }
}
