package org.tekfive.konnekt.message.email.providers.zeptomail.model

data class ZeptoMailEmailStatusResponse(
    val requestId: String? = null,
    val emailReference: String? = null,
    val status: String? = null,
    val openCount: Int = 0,
    val hasDeliveredRecipients: Boolean = false,
    val hasHardBounceRecipients: Boolean = false,
    val hasSoftBounceRecipients: Boolean = false,
    val hasMailFailureRecipients: Boolean = false,
    val hasProcessFailedRecipients: Boolean = false,
)
