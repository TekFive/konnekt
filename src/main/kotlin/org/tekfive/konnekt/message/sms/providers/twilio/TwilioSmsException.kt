package org.tekfive.konnekt.message.sms.providers.twilio

/**
 * Failure raised by the Twilio SMS integration.
 *
 * [message] must never include the Twilio response body, phone numbers, or credentials —
 * exception messages are persisted and logged. [statusCode] carries the HTTP status of a
 * failed Twilio call (null for non-HTTP failures) so callers can classify retryability.
 */
class TwilioSmsException(message: String, val statusCode: Int? = null) : RuntimeException(message)
