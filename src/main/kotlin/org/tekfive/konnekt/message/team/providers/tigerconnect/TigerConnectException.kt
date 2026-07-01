package org.tekfive.konnekt.message.team.providers.tigerconnect

/**
 * Raised for TigerConnect API failures. [statusCode] is the HTTP status when the failure was
 * at the HTTP layer, or null for non-HTTP failures.
 *
 * Messages are persisted and logged in a PHI environment — they must never contain HTTP
 * response bodies, recipient addresses, message content, or credentials.
 */
class TigerConnectException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
