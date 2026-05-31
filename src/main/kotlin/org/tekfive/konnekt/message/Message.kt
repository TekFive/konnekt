package org.tekfive.konnekt.message

import org.tekfive.jfk.ToJsonObject

abstract class Message(
    val to: List<MessageRecipient>,
    val from: MessageAddress,
    val body: String,
) : ToJsonObject {
    abstract val type: MessageType
}
