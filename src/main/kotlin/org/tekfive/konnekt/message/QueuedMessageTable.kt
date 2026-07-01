package org.tekfive.konnekt.message

import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.createdAt
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.data.description
import org.tekfive.keep.data.name
import org.tekfive.keep.data.timestamp
import org.tekfive.keep.json.jsonObject
import org.tekfive.keep.encryption.encryptedJsonObject
import org.tekfive.keep.encryption.encryptedStringList

object QueuedMessageTable : DataTable<QueuedMessage>("queued_messages") {
    // Recipients and message content carry PHI/PII and are encrypted at rest,
    // matching MessageReceiptTable.recipientAddress.
    val recipients = encryptedStringList("recipients")
    val label = name("label")
    val providerTypeConfigurationId = varchar("endpoint_id", 100)
    val description = description()
    val type = dataEnum<MessageType>("type")
    val trackReceipt = bool("track_receipt")
    val message = encryptedJsonObject("content")
    val state = dataEnum<QueuedMessageState>("state")
    val createdAt = createdAt()
    val lastStateChangeAt = timestamp("last_state_change_at")
    val deliverAfter = long("deliver_after").nullable()
    val maxAttempts = integer("max_attempts")
    val receiptDetails = jsonObject("receipt_details").nullable()
    val attemptCount = integer("attempt_count")
    val maxReceiptWaitMinutes = integer("max_receipt_wait_minutes").nullable()
}
