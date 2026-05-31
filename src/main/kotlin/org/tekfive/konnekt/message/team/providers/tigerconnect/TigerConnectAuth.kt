package org.tekfive.konnekt.message.team.providers.tigerconnect

import okhttp3.Credentials

data class TigerConnectAuth(
    val apiKey: String,
    val apiSecret: String,
    val baseUrl: String = DEFAULT_BASE_URL,
) {

    val authorizationHeader: String
        get() = Credentials.basic(apiKey, apiSecret)

    val normalizedBaseUrl: String
        get() = baseUrl.trimEnd('/')

    companion object {
        const val DEFAULT_BASE_URL = "https://api.tigertext.me"
    }
}
