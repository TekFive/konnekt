package org.tekfive.konnekt.message.email.providers.zeptomail.model

data class ZeptoMailSendResponse(
    val requestId: String? = null,
    val status: String? = null,
) {

    val resolvedMessageId: String?
        get() = requestId?.takeIf { it.isNotBlank() }
}
