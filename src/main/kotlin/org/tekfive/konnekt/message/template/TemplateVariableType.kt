package org.tekfive.konnekt.message.template

enum class TemplateVariableType {
    STRING,
    NUMBER,
    BOOLEAN,
    TEMPORAL,
    LIST,
    ;

    val supportsFormatSpecifier: Boolean
        get() = this == NUMBER || this == TEMPORAL || this == BOOLEAN
}
