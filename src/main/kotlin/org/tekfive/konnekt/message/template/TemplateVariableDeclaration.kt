package org.tekfive.konnekt.message.template

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.JsonString
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.json

class TemplateVariableDeclaration(
    val name: String,
    val type: TemplateVariableType,
    val required: Boolean,
    val description: String? = null,
    val sensitivityTags: List<String> = emptyList(),
) : ToJsonObject {

    override fun toJsonObject(): JsonObject {
        return json {
            "name" set name
            "type" setEnum type
            "required" set required
            "description" set description
            "sensitivityTags" set JsonArray(sensitivityTags.map { JsonString(it) })
        }
    }

    companion object : FromJsonObject<TemplateVariableDeclaration>
}
