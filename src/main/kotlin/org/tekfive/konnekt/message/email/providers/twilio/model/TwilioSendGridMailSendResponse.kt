package org.tekfive.konnekt.message.email.providers.twilio.model

data class TwilioSendGridMailSendResponse(
    val messageId: String? = null,
    val status: String? = null,
) {
    val resolvedMessageId: String?
        get() = messageId?.takeIf { it.isNotBlank() }
}
