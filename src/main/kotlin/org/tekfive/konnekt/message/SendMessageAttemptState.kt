package org.tekfive.konnekt.message

import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType

enum class SendMessageAttemptState(
    override val id: Int,
    override val displayName: String,
    val description: String,
) : DataEnum {
    SENDING(1, "Sending", "The system is currently attempting to send the message."),
    SENT(2, "Sent", "The send attempt completed successfully."),
    FAILED(3, "Failed", "The send attempt failed."),
    ;

    companion object : DataEnumColumnType<SendMessageAttemptState>()
}
