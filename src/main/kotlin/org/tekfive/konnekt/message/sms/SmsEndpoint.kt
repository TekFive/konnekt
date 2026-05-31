package org.tekfive.konnekt.message.sms

import org.tekfive.jfk.JsonObject

data class SmsEndpoint(
    val id: String,
    val provider: SmsServiceProvider,
    val config: JsonObject,
)
