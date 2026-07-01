package org.tekfive.konnekt.message.template

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DateTimeException
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/** Thrown when a template cannot be rendered due to missing required variables or other runtime errors. */
class TemplateRenderException(message: String) : RuntimeException(message)

/** Thrown when a template has unresolved validation issues that prevent rendering. */
class TemplateValidationException(val issues: List<TemplateValidationIssue>) :
    RuntimeException("Template has ${issues.size} validation issue(s)")

/**
 * Stateless renderer that validates and renders MessageTemplate instances by substituting
 * declared variables into subject, htmlBody, and textBody templates.
 *
 * Injection safety: substituted values are never re-scanned for placeholder or block-tag
 * syntax. Loop blocks are expanded into side buffers and re-attached via opaque tokens after
 * all placeholder substitution has completed, and every substitution uses lambda-based
 * `Regex.replace` (which does not re-scan its own output).
 */
object TemplateRenderer {

    /** Matches {{variable}}, {{variable|format}}, and {{.}} placeholders, excluding block tags. */
    private val PLACEHOLDER_REGEX = Regex("""\{\{([^{}#/][^{}]*?)\}\}""")

    private val IF_OPEN_TAG_REGEX = Regex("""\{\{\s*#if\s+(!?)(\w+)\s*\}\}""")
    private val IF_CLOSE_TAG_REGEX = Regex("""\{\{\s*/if\s*\}\}""")
    private val EACH_OPEN_TAG_REGEX = Regex("""\{\{\s*#each\s+(\w+)\s*\}\}""")
    private val EACH_CLOSE_TAG_REGEX = Regex("""\{\{\s*/each\s*\}\}""")

    /** Matches {{#if varName}}...{{/if}} and {{#if !varName}}...{{/if}} blocks (whitespace-tolerant). */
    private val IF_BLOCK_REGEX =
        Regex("""\{\{\s*#if\s+(!?)(\w+)\s*\}\}(.*?)\{\{\s*/if\s*\}\}""", RegexOption.DOT_MATCHES_ALL)

    /** Matches {{#each varName}}...{{/each}} blocks (whitespace-tolerant). */
    private val EACH_BLOCK_REGEX =
        Regex("""\{\{\s*#each\s+(\w+)\s*\}\}(.*?)\{\{\s*/each\s*\}\}""", RegexOption.DOT_MATCHES_ALL)

    /** ASCII control characters (defense in depth against SMTP header injection in subjects). */
    private val ASCII_CONTROL_CHARS_REGEX = Regex("[\u0000-\u001F\u007F]")

    /**
     * Delimiter for opaque loop-expansion tokens. It is stripped from template input and from every
     * substituted value, so neither a template author nor a variable value can forge a token.
     */
    private const val LOOP_TOKEN_DELIMITER = "\uE000"

    /**
     * Validates the template: duplicate variable declarations, block-tag structure (unclosed,
     * orphaned, and nested blocks), block-tag variable names, {{.}} scope, undeclared placeholder
     * variables, and format specifiers.
     */
    fun validate(template: MessageTemplate): List<TemplateValidationIssue> {
        val issues = mutableListOf<TemplateValidationIssue>()

        val declaredVars = mutableMapOf<String, TemplateVariableDeclaration>()
        for (decl in template.variables) {
            if (declaredVars.containsKey(decl.name)) {
                issues.add(
                    TemplateValidationIssue(
                        "{{${decl.name}}}", "variables",
                        "Duplicate variable declaration: ${decl.name}"
                    )
                )
            } else {
                declaredVars[decl.name] = decl
            }
        }

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
     * 1. Always run validation fresh; throw on any issue (never mutates the template entity)
     * 2. Validate required variables and value types
     * 3. Per field: process conditionals, expand loops into opaque tokens, substitute values,
     *    then re-attach loop expansions (no substituted value is ever re-scanned)
     * 4. Strip ASCII control characters from the rendered subject
     * 5. Collect sensitivity tags
     * 6. Return RenderedMessage
     */
    fun render(template: MessageTemplate, variables: Map<String, Any>): RenderedMessage {
        // Step 1: Always validate fresh — stored validation state may be stale in either direction.
        val issues = validate(template)
        if (issues.isNotEmpty()) {
            throw TemplateValidationException(issues)
        }

        // Validation guarantees declaration names are unique.
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

        // Steps 3-4: Render each field; strip control characters from the subject only
        // (bodies may legitimately contain newlines).
        val subject = ASCII_CONTROL_CHARS_REGEX.replace(
            renderField(template.subjectTemplate, variables, declaredVars, htmlEscape = false), ""
        )
        val htmlBody = renderField(template.htmlBodyTemplate, variables, declaredVars, htmlEscape = true)
        val textBody = renderField(template.textBodyTemplate, variables, declaredVars, htmlEscape = false)

        // Step 5: Collect sensitivity tags from present variables
        val sensitivityTags = mutableSetOf<String>()
        for (decl in template.variables) {
            if (variables.containsKey(decl.name)) {
                sensitivityTags.addAll(decl.sensitivityTags)
            }
        }

        // Step 6: Return RenderedMessage
        return RenderedMessage(
            subject = subject,
            htmlBody = htmlBody,
            textBody = textBody,
            sensitivityTags = sensitivityTags,
        )
    }

    /**
     * Renders a single template field. Loop blocks are expanded into a side map keyed by opaque
     * tokens so that expanded item values are never re-scanned by the outer placeholder pass, and
     * outer substituted values are never re-scanned by the loop pass.
     */
    private fun renderField(
        content: String,
        variables: Map<String, Any>,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        htmlEscape: Boolean,
    ): String {
        // The token delimiter must not be forgeable from template text.
        val sanitized = content.replace(LOOP_TOKEN_DELIMITER, "")

        val afterConditionals = processConditionals(sanitized, variables)

        // Expand each loop block fully (both {{.}} and regular {{var}} placeholders inside the
        // body) and replace the block with an opaque token.
        val loopExpansions = mutableMapOf<String, String>()
        var loopIndex = 0
        val afterLoops = EACH_BLOCK_REGEX.replace(afterConditionals) { match ->
            val varName = match.groupValues[1]
            val body = match.groupValues[2]

            val value = variables[varName]
            val items = if (value is List<*>) value else emptyList<Any>()

            val expanded = StringBuilder()
            for (item in items) {
                val itemText = item?.toString() ?: ""
                expanded.append(substituteValues(body, variables, declaredVars, htmlEscape, itemText))
            }

            val token = "${LOOP_TOKEN_DELIMITER}LOOP${loopIndex}${LOOP_TOKEN_DELIMITER}"
            loopIndex++
            loopExpansions[token] = expanded.toString()
            token
        }

        var result = substituteValues(afterLoops, variables, declaredVars, htmlEscape)

        // Re-attach the loop expansions with literal string replacement — no re-scan.
        for ((token, expansion) in loopExpansions) {
            result = result.replace(token, expansion)
        }
        return result
    }

    /**
     * Processes {{#if varName}}...{{/if}} conditionals, including negated {{#if !varName}} form.
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

    /**
     * Handlebars-like truthiness: null, false, zero Numbers, empty Strings, and empty Lists are
     * falsy; everything else is truthy.
     */
    private fun isTruthy(value: Any?): Boolean {
        return when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotEmpty()
            is List<*> -> value.isNotEmpty()
            else -> true
        }
    }

    /**
     * Substitutes {{var}}, {{var|format}}, and (inside loop bodies) {{.}} placeholders in a single
     * lambda-based pass, so substituted values are never re-scanned for placeholder syntax. The
     * loop-token delimiter is stripped from every substituted value so values cannot forge tokens.
     */
    private fun substituteValues(
        content: String,
        variables: Map<String, Any>,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        htmlEscape: Boolean,
        loopItemText: String? = null,
    ): String {
        return PLACEHOLDER_REGEX.replace(content) { match ->
            val inner = match.groupValues[1].trim()
            val parts = inner.split("|", limit = 2)
            val varName = parts[0].trim()
            val formatSpec = if (parts.size > 1) parts[1].trim() else null

            val formatted: String
            if (varName == ".") {
                formatted = loopItemText ?: ""
            } else {
                val value = variables[varName]
                val declaration = declaredVars[varName]
                formatted = formatValue(varName, value, declaration?.type, formatSpec)
            }

            val cleaned = formatted.replace(LOOP_TOKEN_DELIMITER, "")
            if (htmlEscape) escapeHtml(cleaned) else cleaned
        }
    }

    private fun formatValue(varName: String, value: Any?, type: TemplateVariableType?, formatSpec: String?): String {
        if (value == null) {
            return ""
        }

        if (type == TemplateVariableType.LIST && value is List<*>) {
            return value.joinToString(", ") { it?.toString() ?: "" }
        }

        if (formatSpec != null && type != null) {
            try {
                return applyFormatSpecifier(value, type, formatSpec)
            } catch (e: DateTimeException) {
                // Covers UnsupportedTemporalTypeException. Never include the value — it may be PHI.
                throw TemplateRenderException("Variable '$varName': value cannot be formatted with pattern '$formatSpec'")
            } catch (e: IllegalArgumentException) {
                throw TemplateRenderException("Variable '$varName': value cannot be formatted with pattern '$formatSpec'")
            }
        }

        return value.toString()
    }

    private fun applyFormatSpecifier(value: Any, type: TemplateVariableType, formatSpec: String): String {
        return when (type) {
            TemplateVariableType.NUMBER -> {
                val df = DecimalFormat(formatSpec, DecimalFormatSymbols(Locale.US))
                df.format(value)
            }
            TemplateVariableType.TEMPORAL -> {
                val formatter = DateTimeFormatter.ofPattern(formatSpec, Locale.US)
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
            TemplateVariableType.TEMPORAL -> if (value !is TemporalAccessor) "expected Temporal, got ${value::class.simpleName}" else null
            TemplateVariableType.LIST -> if (value !is List<*>) "expected List, got ${value::class.simpleName}" else null
        }
    }

    private fun validateField(
        content: String,
        location: String,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        issues: MutableList<TemplateValidationIssue>,
    ) {
        validateBlockStructure(content, location, "if", IF_OPEN_TAG_REGEX, IF_CLOSE_TAG_REGEX, declaredVars, issues)
        validateBlockStructure(content, location, "each", EACH_OPEN_TAG_REGEX, EACH_CLOSE_TAG_REGEX, declaredVars, issues)

        // Validate placeholders inside each-block bodies (where {{.}} is allowed), then remove the
        // blocks and validate the remainder (where {{.}} is a stray token).
        val remainder = EACH_BLOCK_REGEX.replace(content) { match ->
            val body = stripBlockTags(match.groupValues[2])
            validatePlaceholders(body, location, declaredVars, insideEach = true, issues)
            ""
        }
        validatePlaceholders(stripBlockTags(remainder), location, declaredVars, insideEach = false, issues)
    }

    /** Removes if/each open and close tags so they are not scanned as placeholders. */
    private fun stripBlockTags(content: String): String {
        var cleaned = IF_OPEN_TAG_REGEX.replace(content, "")
        cleaned = IF_CLOSE_TAG_REGEX.replace(cleaned, "")
        cleaned = EACH_OPEN_TAG_REGEX.replace(cleaned, "")
        cleaned = EACH_CLOSE_TAG_REGEX.replace(cleaned, "")
        return cleaned
    }

    /**
     * Validates block-tag structure for one tag type: opener variable names must be declared,
     * blocks must be balanced (no unclosed openers or orphaned closers), and nested blocks of the
     * same type are not supported by the renderer.
     */
    private fun validateBlockStructure(
        content: String,
        location: String,
        tagName: String,
        openRegex: Regex,
        closeRegex: Regex,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        issues: MutableList<TemplateValidationIssue>,
    ) {
        // Collect open/close tag events in document order. (position, tag text, opener, varName)
        val events = mutableListOf<Triple<Int, String, String?>>()
        for (match in openRegex.findAll(content)) {
            events.add(Triple(match.range.first, match.value, match.groupValues.last()))
        }
        for (match in closeRegex.findAll(content)) {
            events.add(Triple(match.range.first, match.value, null))
        }
        events.sortBy { it.first }

        val openStack = ArrayDeque<String>()
        for ((_, tagText, varName) in events) {
            if (varName != null) {
                if (!declaredVars.containsKey(varName)) {
                    issues.add(TemplateValidationIssue(tagText, location, "Undeclared variable: $varName"))
                }
                if (openStack.isNotEmpty()) {
                    issues.add(
                        TemplateValidationIssue(
                            tagText, location,
                            "Nested {{#$tagName}} blocks are not supported"
                        )
                    )
                }
                openStack.addLast(tagText)
            } else {
                if (openStack.isEmpty()) {
                    issues.add(
                        TemplateValidationIssue(
                            tagText, location,
                            "Orphaned {{/$tagName}} without matching {{#$tagName}}"
                        )
                    )
                } else {
                    openStack.removeLast()
                }
            }
        }
        for (unclosed in openStack) {
            issues.add(TemplateValidationIssue(unclosed, location, "Unclosed block: $unclosed has no matching {{/$tagName}}"))
        }
    }

    private fun validatePlaceholders(
        content: String,
        location: String,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        insideEach: Boolean,
        issues: MutableList<TemplateValidationIssue>,
    ) {
        for (match in PLACEHOLDER_REGEX.findAll(content)) {
            val inner = match.groupValues[1].trim()
            val parts = inner.split("|", limit = 2)
            val varName = parts[0].trim()
            val formatSpec = if (parts.size > 1) parts[1].trim() else null

            if (varName == ".") {
                if (!insideEach) {
                    issues.add(
                        TemplateValidationIssue(
                            "{{.}}", location,
                            "{{.}} is only valid inside an {{#each}} block"
                        )
                    )
                }
                continue
            }

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
                    DecimalFormat(formatSpec, DecimalFormatSymbols(Locale.US))
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
                    DateTimeFormatter.ofPattern(formatSpec, Locale.US)
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
