package org.tekfive.konnekt.message.sms

import org.tekfive.jfk.JsonObject

/**
 * Stateless sender interface for SMS providers.
 *
 * Implementations should read provider configuration from the supplied JsonObject.
 */
interface SmsSender {

    val id: String

    val active: Boolean

    val capabilities: Set<SmsCapability>

    fun send(message: SmsMessage, config: JsonObject): SmsResponse

    fun status(messageId: String, config: JsonObject): SmsStatus?
}
