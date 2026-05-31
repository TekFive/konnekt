package org.tekfive.konnekt.message.template

import java.text.DecimalFormat
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor

/** Thrown when a template cannot be rendered due to missing required variables or other runtime errors. */
class TemplateRenderException(message: String) : RuntimeException(message)

/** Thrown when a template has unresolved validation issues that prevent rendering. */
class TemplateValidationException(val issues: List<TemplateValidationIssue>) :
    RuntimeException("Template has ${issues.size} validation issue(s)")

/**
 * Stateless renderer that validates and renders MessageTemplate instances by substituting
 * declared variables into subject, htmlBody, and textBody templates.
 */
object TemplateRenderer {

    /** Matches {{variable}} and {{variable|format}} placeholders, excluding block tags. */
    private val PLACEHOLDER_REGEX = Regex("""\{\{([^{}#/][^{}]*?)\}\}""")

    /** Matches block tags that should be skipped during variable validation. */
    private val BLOCK_TAG_REGEX = Regex("""\{\{(#if\s+!?\w+|/if|#each\s+\w+|/each|\.)\}\}""")

    /**
     * Validates that all placeholders in the template reference declared variables
     * and that any format specifiers are valid for the variable's type.
     */
    fun validate(template: MessageTemplate): List<TemplateValidationIssue> {
        val issues = mutableListOf<TemplateValidationIssue>()
        val declaredVars = template.variables.associateBy { it.name }

        validateField(template.subjectTemplate, "subject", declaredVars, issues)
        validateField(template.htmlBodyTemplate, "htmlBody", declaredVars, issues)
        validateField(template.textBodyTemplate, "textBody", declaredVars, issues)

        return issues
    }

    /**
     * Renders a template by substituting variable values into the subject, htmlBody, and textBody
     * templates, returning a RenderedMessage with collected sensitivity tags.
     *
     * Processing order:
     * 1. Check stored validation issues (re-validate if present)
     * 2. Validate required variables
     * 3. Process conditionals
     * 4. Process loops
     * 5. Substitute and format values
     * 6. Collect sensitivity tags
     * 7. Return RenderedMessage
     */
    fun render(template: MessageTemplate, variables: Map<String, Any>): RenderedMessage {
        // Step 1: Check stored validation issues
        if (template.validationIssues.isNotEmpty()) {
            val freshIssues = validate(template)
            if (freshIssues.isNotEmpty()) {
                throw TemplateValidationException(freshIssues)
            }
            template.validationIssues = emptyList()
        }

        val declaredVars = template.variables.associateBy { it.name }

        // Step 2: Validate required variables are present
        for (decl in template.variables) {
            if (decl.required && !variables.containsKey(decl.name)) {
                throw TemplateRenderException("Missing required variable: ${decl.name}")
            }
        }

        // Step 2b: Validate variable types are compatible
        for (decl in template.variables) {
            val value = variables[decl.name] ?: continue
            val typeError = validateValueType(value, decl.type)
            if (typeError != null) {
                throw TemplateRenderException("Variable '${decl.name}': $typeError")
            }
        }

        // Step 3: Process conditionals
        val subjectAfterCond = processConditionals(template.subjectTemplate, variables)
        val htmlBodyAfterCond = processConditionals(template.htmlBodyTemplate, variables)
        val textBodyAfterCond = processConditionals(template.textBodyTemplate, variables)

        // Step 4: Process loops
        val subjectAfterLoops = processLoops(subjectAfterCond, variables, htmlEscape = false)
        val htmlBodyAfterLoops = processLoops(htmlBodyAfterCond, variables, htmlEscape = true)
        val textBodyAfterLoops = processLoops(textBodyAfterCond, variables, htmlEscape = false)

        // Step 5: Substitute and format values
        val subject = substituteValues(subjectAfterLoops, variables, declaredVars, htmlEscape = false)
        val htmlBody = substituteValues(htmlBodyAfterLoops, variables, declaredVars, htmlEscape = true)
        val textBody = substituteValues(textBodyAfterLoops, variables, declaredVars, htmlEscape = false)

        // Step 6: Collect sensitivity tags from present variables
        val sensitivityTags = mutableSetOf<String>()
        for (decl in template.variables) {
            if (variables.containsKey(decl.name)) {
                sensitivityTags.addAll(decl.sensitivityTags)
            }
        }

        // Step 7: Return RenderedMessage
        return RenderedMessage(
            subject = subject,
            htmlBody = htmlBody,
            textBody = textBody,
            sensitivityTags = sensitivityTags,
        )
    }

    /** Matches {{#if varName}}...{{/if}} and {{#if !varName}}...{{/if}} blocks. */
    private val IF_BLOCK_REGEX = Regex("""\{\{#if\s+(!?)(\w+)\}\}(.*?)\{\{/if\}\}""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Processes {{#if varName}}...{{/if}} conditionals, including negated {{#if !varName}} form.
     * Truthiness: null -> false, false -> false, empty string -> false, everything else -> true.
     */
    private fun processConditionals(content: String, variables: Map<String, Any>): String {
        return IF_BLOCK_REGEX.replace(content) { match ->
            val negated = match.groupValues[1] == "!"
            val varName = match.groupValues[2]
            val body = match.groupValues[3]

            val value = variables[varName]
            val truthy = isTruthy(value)
            val include = if (negated) !truthy else truthy

            if (include) body else ""
        }
    }

    private fun isTruthy(value: Any?): Boolean {
        return when (value) {
            null -> false
            is Boolean -> value
            is String -> value.isNotEmpty()
            else -> true
        }
    }

    /** Matches {{#each varName}}...{{/each}} blocks. */
    private val EACH_BLOCK_REGEX = Regex("""\{\{#each\s+(\w+)\}\}(.*?)\{\{/each\}\}""", RegexOption.DOT_MATCHES_ALL)

    /** Matches {{.}} for current loop item reference. */
    private val DOT_PLACEHOLDER_REGEX = Regex("""\{\{\.\}\}""")

    /**
     * Processes {{#each varName}}...{{/each}} loop blocks. Each item in the list
     * replaces {{.}} references within the block body. HTML-escapes items when rendering htmlBody.
     * Empty or absent lists produce no output.
     */
    private fun processLoops(content: String, variables: Map<String, Any>, htmlEscape: Boolean): String {
        return EACH_BLOCK_REGEX.replace(content) { match ->
            val varName = match.groupValues[1]
            val body = match.groupValues[2]

            val value = variables[varName]
            val items = when (value) {
                is List<*> -> value
                else -> emptyList<Any>()
            }

            items.joinToString("") { item ->
                val itemStr = item?.toString() ?: ""
                val escapedItem = if (htmlEscape) escapeHtml(itemStr) else itemStr
                DOT_PLACEHOLDER_REGEX.replace(body, Regex.escapeReplacement(escapedItem))
            }
        }
    }

    private fun substituteValues(
        content: String,
        variables: Map<String, Any>,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        htmlEscape: Boolean,
    ): String {
        return PLACEHOLDER_REGEX.replace(content) { match ->
            val inner = match.groupValues[1].trim()
            val parts = inner.split("|", limit = 2)
            val varName = parts[0].trim()
            val formatSpec = if (parts.size > 1) parts[1].trim() else null

            val value = variables[varName]
            val declaration = declaredVars[varName]

            val formatted = formatValue(value, declaration?.type, formatSpec)
            if (htmlEscape) escapeHtml(formatted) else formatted
        }
    }

    private fun formatValue(value: Any?, type: TemplateVariableType?, formatSpec: String?): String {
        if (value == null) {
            return ""
        }

        if (type == TemplateVariableType.LIST && value is List<*>) {
            return value.joinToString(", ") { it?.toString() ?: "" }
        }

        if (formatSpec != null && type != null) {
            return applyFormatSpecifier(value, type, formatSpec)
        }

        return value.toString()
    }

    private fun applyFormatSpecifier(value: Any, type: TemplateVariableType, formatSpec: String): String {
        return when (type) {
            TemplateVariableType.NUMBER -> {
                val df = DecimalFormat(formatSpec)
                df.format(value)
            }
            TemplateVariableType.TEMPORAL -> {
                val formatter = DateTimeFormatter.ofPattern(formatSpec)
                formatter.format(value as TemporalAccessor)
            }
            TemplateVariableType.BOOLEAN -> {
                val parts = formatSpec.split("/", limit = 2)
                val trueLabel = parts[0]
                val falseLabel = if (parts.size > 1) parts[1] else ""
                if (value == true) trueLabel else falseLabel
            }
            else -> value.toString()
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }

    private fun validateValueType(value: Any, type: TemplateVariableType): String? {
        return when (type) {
            TemplateVariableType.STRING -> if (value !is String) "expected String, got ${value::class.simpleName}" else null
            TemplateVariableType.NUMBER -> if (value !is Number) "expected Number, got ${value::class.simpleName}" else null
            TemplateVariableType.BOOLEAN -> if (value !is Boolean) "expected Boolean, got ${value::class.simpleName}" else null
            TemplateVariableType.TEMPORAL -> if (value !is java.time.temporal.TemporalAccessor) "expected Temporal, got ${value::class.simpleName}" else null
            TemplateVariableType.LIST -> if (value !is List<*>) "expected List, got ${value::class.simpleName}" else null
        }
    }

    private fun validateField(
        content: String,
        location: String,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        issues: MutableList<TemplateValidationIssue>,
    ) {
        // Remove block tags so they don't match as placeholders
        val cleaned = BLOCK_TAG_REGEX.replace(content, "")

        for (match in PLACEHOLDER_REGEX.findAll(cleaned)) {
            val inner = match.groupValues[1].trim()
            val parts = inner.split("|", limit = 2)
            val varName = parts[0].trim()
            val formatSpec = if (parts.size > 1) parts[1].trim() else null

            val placeholder = if (formatSpec != null) "{{${varName}|${formatSpec}}}" else "{{${varName}}}"
            val declaration = declaredVars[varName]
            if (declaration == null) {
                issues.add(TemplateValidationIssue(placeholder, location, "Undeclared variable: $varName"))
                continue
            }

            if (formatSpec != null) {
                validateFormatSpecifier(placeholder, formatSpec, declaration.type, location, issues)
            }
        }
    }

    private fun validateFormatSpecifier(
        placeholder: String,
        formatSpec: String,
        type: TemplateVariableType,
        location: String,
        issues: MutableList<TemplateValidationIssue>,
    ) {
        if (!type.supportsFormatSpecifier) {
            issues.add(
                TemplateValidationIssue(
                    placeholder, location,
                    "${type.name} variables do not support format specifiers"
                )
            )
            return
        }

        when (type) {
            TemplateVariableType.NUMBER -> {
                try {
                    DecimalFormat(formatSpec)
                } catch (e: IllegalArgumentException) {
                    issues.add(
                        TemplateValidationIssue(
                            placeholder, location,
                            "Invalid number format: $formatSpec"
                        )
                    )
                }
            }
            TemplateVariableType.TEMPORAL -> {
                try {
                    DateTimeFormatter.ofPattern(formatSpec)
                } catch (e: IllegalArgumentException) {
                    issues.add(
                        TemplateValidationIssue(
                            placeholder, location,
                            "Invalid temporal format: $formatSpec"
                        )
                    )
                }
            }
            TemplateVariableType.BOOLEAN -> {
                if (!formatSpec.contains("/")) {
                    issues.add(
                        TemplateValidationIssue(
                            placeholder, location,
                            "Boolean format must contain '/' separator (e.g. Yes/No)"
                        )
                    )
                }
            }
            else -> {}
        }
    }
}
