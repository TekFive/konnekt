package org.tekfive.konnekt.message

import org.tekfive.keep.data.Data

class MessageReceipt(
    val queuedMessageId: Long,
    var providerId: String,
    val recipientAddress: String,
    val status: MessageReceiptStatus,
    val details: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
) : Data() {
}