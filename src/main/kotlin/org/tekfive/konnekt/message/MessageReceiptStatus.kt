package org.tekfive.konnekt.message

import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType

enum class MessageReceiptStatus(
    override val id: Int,
    override val displayName: String,
    val description: String,
) : DataEnum {
    WAITING(1, "Waiting", "The message has been sent and is waiting for a delivery or receipt update."),
    DELIVERY_FAILURE(2, "Delivery Failure", "The provider reported that delivery failed."),
    RECEIVED(3, "Received", "The recipient or downstream system confirmed that the message was received."),
    TIMED_OUT(4, "Timed Out", "Tracking did not complete before the timeout expired."),
    ;

    companion object : DataEnumColumnType<MessageReceiptStatus>()
}
