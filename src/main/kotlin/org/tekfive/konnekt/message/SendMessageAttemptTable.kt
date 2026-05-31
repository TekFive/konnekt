package org.tekfive.konnekt.message

import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.createdAt
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.data.fkey
import org.tekfive.keep.data.timestamp

object SendMessageAttemptTable : DataTable<SendMessageAttempt>("queued_message_attempts") {
    val queuedMessageId = fkey("queued_message_id", QueuedMessageTable)
    val state = dataEnum<SendMessageAttemptState>("state_id")
    val details = text("details").nullable()
    val startedAt = timestamp("started_at")
    val endedAt = timestamp("ended_at").nullable()
}
