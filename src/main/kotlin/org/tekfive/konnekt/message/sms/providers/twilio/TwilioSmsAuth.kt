package org.tekfive.konnekt.message.sms.providers.twilio

import java.net.URI
import java.net.URISyntaxException
import java.util.Base64

/**
 * Twilio SMS credentials plus the API base URL used by the sender and client.
 *
 * Deliberately not a `data class`: the generated `toString()` would print [authToken],
 * and exception messages and logs must never contain credentials.
 */
class TwilioSmsAuth(
    val accountSid: String,
    val authToken: String,
    val baseUrl: String? = DEFAULT_BASE_URL,
) {

    init {
        requireHttps(normalizedBaseUrl)
    }

    val authorizationHeader: String
        get() = "Basic ${encodeCredentials(accountSid, authToken)}"

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')

    override fun toString(): String {
        return "TwilioSmsAuth(accountSid=$accountSid, authToken=REDACTED, baseUrl=$baseUrl)"
    }

    private fun encodeCredentials(accountSid: String, authToken: String): String {
        val token = "$accountSid:$authToken"
        return Base64.getEncoder().encodeToString(token.toByteArray(Charsets.UTF_8))
    }

    /**
     * Requests carry Basic auth credentials, so a plain-http base URL would leak them.
     * Only loopback hosts (local test servers such as MockWebServer) are exempt.
     */
    private fun requireHttps(url: String) {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("Twilio SMS baseUrl is not a valid URL", e)
        }
        val isLoopback = uri.host == "localhost" || uri.host == "127.0.0.1"
        require(uri.scheme == "https" || isLoopback) {
            "Twilio SMS baseUrl must use https (credentials are sent with every request)"
        }
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.twilio.com"
    }
}
