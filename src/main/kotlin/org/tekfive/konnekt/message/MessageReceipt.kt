package org.tekfive.konnekt.message

import org.tekfive.keep.data.Data
import org.tekfive.keep.data.TrackUpdatedAt
import org.tekfive.keep.db.dbTransactionAt

class MessageReceipt(
    val queuedMessageId: Long,
    var providerId: String,
    val recipientAddress: String,
    var status: MessageReceiptStatus,
    var details: String? = null,
    val createdAt: Long = dbTransactionAt(),
    override var updatedAt: Long = createdAt,
) : Data(), TrackUpdatedAt {
}
