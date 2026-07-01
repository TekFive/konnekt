package org.tekfive.konnekt.message.email.providers

import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.message.email.EmailMessage
import org.tekfive.konnekt.message.email.EmailResponse
import org.tekfive.konnekt.message.email.EmailStatus

interface EmailProvider {
    val supportsTracking: Boolean

    fun send(message: EmailMessage, providerConfiguration: JsonObject): EmailResponse

    fun status(messageId: String, providerConfiguration: JsonObject): EmailStatus?
}