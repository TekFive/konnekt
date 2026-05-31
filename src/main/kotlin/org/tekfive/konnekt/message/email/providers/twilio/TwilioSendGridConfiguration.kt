package org.tekfive.konnekt.message.email.providers.twilio

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

data class TwilioSendGridConfiguration(
    val apiKey: String,
    val baseUrl: String? = DEFAULT_BASE_URL,
) : ToJsonObject {

    companion object : FromJsonObject<TwilioSendGridConfiguration> {
        const val DEFAULT_BASE_URL: String = "https://api.sendgrid.com"
    }

    val authorizationHeader: String
        get() = "Bearer $apiKey"

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')
}
