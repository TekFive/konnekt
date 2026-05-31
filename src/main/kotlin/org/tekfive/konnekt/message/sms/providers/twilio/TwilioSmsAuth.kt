package org.tekfive.konnekt.message.sms.providers.twilio

import java.util.Base64

/**
 * Twilio SMS credentials plus the API base URL used by the sender and client.
 */
data class TwilioSmsAuth(
    val accountSid: String,
    val authToken: String,
    val baseUrl: String? = DEFAULT_BASE_URL,
) {
    val authorizationHeader: String
        get() = "Basic ${encodeCredentials(accountSid, authToken)}"

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')

    private fun encodeCredentials(accountSid: String, authToken: String): String {
        val token = "$accountSid:$authToken"
        return Base64.getEncoder().encodeToString(token.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.twilio.com"
    }
}
