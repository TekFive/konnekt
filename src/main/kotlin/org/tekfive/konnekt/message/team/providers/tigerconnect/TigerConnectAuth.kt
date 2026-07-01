package org.tekfive.konnekt.message.team.providers.tigerconnect

import okhttp3.Credentials

/**
 * TigerConnect connection credentials. Deliberately not a `data class` so a generated
 * `toString()` cannot leak the API key/secret into logs or persisted error messages.
 */
class TigerConnectAuth(
    val apiKey: String,
    val apiSecret: String,
    val baseUrl: String = DEFAULT_BASE_URL,
) {

    val authorizationHeader: String
        get() = Credentials.basic(apiKey, apiSecret)

    val normalizedBaseUrl: String
        get() = baseUrl.trimEnd('/')

    override fun toString(): String {
        return "TigerConnectAuth(apiKey=<redacted>, apiSecret=<redacted>, baseUrl=$baseUrl)"
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.tigertext.me"
    }
}
