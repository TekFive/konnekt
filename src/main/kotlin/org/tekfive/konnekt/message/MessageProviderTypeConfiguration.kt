package org.tekfive.konnekt.message

import org.tekfive.jfk.JsonObject

abstract class MessageProviderTypeConfiguration(
    val id: String,
    val configuration: JsonObject,
) {
}