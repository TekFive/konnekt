package org.tekfive.konnekt.message.email

import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.message.MessageProviderTypeConfiguration

class EmailProviderTypeConfiguration(
    id: String,
    val type: EmailProviderType,
    configuration: JsonObject,
) : MessageProviderTypeConfiguration(id, configuration) {}
