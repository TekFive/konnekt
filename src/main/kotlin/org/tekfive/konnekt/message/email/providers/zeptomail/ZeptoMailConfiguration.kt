package org.tekfive.konnekt.message.email.providers.zeptomail

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

data class ZeptoMailConfiguration(
    val sendMailToken: String,
    val oauthAccessToken: String? = null,
    val baseUrl: String? = DEFAULT_BASE_URL,
) : ToJsonObject {

    companion object : FromJsonObject<ZeptoMailConfiguration> {
        const val DEFAULT_BASE_URL: String = "https://api.zeptomail.com"
    }

    val sendAuthorizationHeader: String
        get() = "Zoho-enczapikey $sendMailToken"

    val oauthAuthorizationHeader: String?
        get() {
            val normalizedToken = oauthAccessToken?.trim().orEmpty()
            if (normalizedToken.isBlank()) {
                return null
            }
            return "Zoho-oauthtoken $normalizedToken"
        }

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')

    override fun toString(): String {
        return "ZeptoMailConfiguration(sendMailToken=REDACTED, oauthAccessToken=REDACTED, baseUrl=$baseUrl)"
    }
}
