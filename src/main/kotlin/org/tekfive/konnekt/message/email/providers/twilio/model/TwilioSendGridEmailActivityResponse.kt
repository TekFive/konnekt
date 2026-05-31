package org.tekfive.konnekt.message.email.providers.twilio.model

data class TwilioSendGridEmailActivityResponse(
    val messageId: String? = null,
    val status: String? = null,
    val events: List<TwilioSendGridEmailEvent> = emptyList(),
)
