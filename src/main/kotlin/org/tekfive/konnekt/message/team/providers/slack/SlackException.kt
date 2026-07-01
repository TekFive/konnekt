package org.tekfive.konnekt.message.team.providers.slack

/**
 * Raised for Slack API failures. [statusCode] is the HTTP status when the failure was at the
 * HTTP layer, or null for Slack API-level errors carried in a 200 response (`ok: false`).
 *
 * Messages are persisted and logged in a PHI environment — they must never contain HTTP
 * response bodies, recipient addresses, message content, or credentials.
 */
class SlackException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
