package org.tekfive.konnekt.message.template

import org.tekfive.keep.data.Data

/** A stored email template with declared variables, subject/body templates, and validation state. */
class MessageTemplate(
    val identifier: String,
    var name: String,
    var description: String? = null,
    var subjectTemplate: String,
    var htmlBodyTemplate: String,
    var textBodyTemplate: String,
    var variables: List<TemplateVariableDeclaration> = emptyList(),
    var validationIssues: List<TemplateValidationIssue> = emptyList(),
    var active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) : Data()
