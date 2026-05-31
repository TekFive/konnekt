package org.tekfive.konnekt.message.sms.providers.twilio.model

data class TwilioSmsSendRequest(
    val to: String,
    val from: String? = null,
    val messagingServiceSid: String? = null,
    val body: String,
)
