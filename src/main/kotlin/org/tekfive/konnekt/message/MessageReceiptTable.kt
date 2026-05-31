package org.tekfive.konnekt.message

import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.createdAt
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.data.fkey
import org.tekfive.keep.data.timestamp
import org.tekfive.keep.encryption.encryptedText

object MessageReceiptTable : DataTable<MessageReceipt>("message_receipt") {
    val queuedMessageId = fkey("queued_message_id", QueuedMessageTable)
    val providerId = varchar("provider_id", 50)
    val recipientAddress = encryptedText("recipient_address")
    val status = dataEnum<MessageReceiptStatus>("status_id")
    val details = text("details").nullable()
    val createdAt = createdAt()
    val updatedAt = timestamp("updated_at")
}
