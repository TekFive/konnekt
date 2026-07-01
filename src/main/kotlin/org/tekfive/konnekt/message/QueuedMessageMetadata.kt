package org.tekfive.konnekt.message

import org.tekfive.jfk.JsonObject

class QueuedMessageMetadata(
    val label: String,
    val description: String? = null,
    val trackReceipt: Boolean = false,
    val deliverAfter: Long? = null,
    val maxAttempts: Int = 1,
    val maxReceiptWaitMinutes: Int? = null,
    val receiptDetails: JsonObject? = null,

) {
}