package org.tekfive.konnekt.message

import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType

enum class QueuedMessageState(
    override val id: Int,
    override val displayName: String,
    val completed: Boolean,
    val description: String = "",
) : DataEnum {
    QUEUED(1, "Queued", false, "Message has been created and is waiting for processing."),
    PENDING(2, "Pending", false, "Message has been scheduled for delivery, but processing has not started yet."),
    PROCESSING(3, "Processing", false, "A worker is currently attempting to deliver the message."),
    FAILED_WAITING_TO_RETRY(4, "Waiting To Retry", false, "A delivery attempt failed, and the message is waiting for another retry."),
    SENT(5, "Sent", true, "Message was successfully sent."),
    TIMED_OUT(6, "Timed Out", true, "Message delivery failed, and no further attempts will be made."),
    FAILED(7, "Failed", true, "Message delivery failed, and no further attempts will be made."),
    CANCELLED(8, "Cancelled", true, "Message delivery was cancelled before it could complete."),
    ;

    companion object : DataEnumColumnType<QueuedMessageState>()
}
