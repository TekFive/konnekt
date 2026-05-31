package org.tekfive.konnekt.message.sms

data class SmsResponse(
    val messageId: String,
    val providerId: String,
    val status: SmsStatus = SmsStatus.UNKNOWN,
)
