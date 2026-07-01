package org.tekfive.konnekt.message.team.providers.slack

/**
 * Slack connection credentials. Deliberately not a `data class` so a generated `toString()`
 * cannot leak the bot token into logs or persisted error messages.
 */
class SlackAuth(
    val botToken: String,
    val baseUrl: String? = DEFAULT_BASE_URL,
) {

    val authorizationHeader: String
        get() = "Bearer $botToken"

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')

    override fun toString(): String {
        return "SlackAuth(botToken=<redacted>, baseUrl=$baseUrl)"
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://slack.com"
    }
}
