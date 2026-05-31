package org.tekfive.konnekt.message.team.providers.slack

data class SlackAuth(
    val botToken: String,
    val baseUrl: String? = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_BASE_URL: String = "https://slack.com"
    }

    val authorizationHeader: String
        get() = "Bearer $botToken"

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')
}
