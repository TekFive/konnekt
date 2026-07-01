package org.tekfive.konnekt.message.email.providers.twilio

/**
 * Failure returned by the SendGrid API. The message carries only the HTTP status code and a
 * short description — never the response body, which may echo recipient or message content.
 */
class TwilioSendGridException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
