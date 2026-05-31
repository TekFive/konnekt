package org.tekfive.konnekt.message.email

data class EmailResponse(
    val messageId: String,
    val providerId: String,
    val status: EmailStatus = EmailStatus.UNKNOWN,
)
