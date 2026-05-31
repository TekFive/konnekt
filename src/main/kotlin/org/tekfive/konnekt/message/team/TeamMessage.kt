package org.tekfive.konnekt.message.team

import org.tekfive.jfk.FromJsonObject
import org.tekfive.konnekt.message.Message
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.MessageType

class TeamMessage(
    to: List<MessageRecipient>,
    from: MessageAddress,
    val subject: String? = null,
    body: String,
    val attachments: List<TeamMessageAttachment> = emptyList(),
    val priority: TeamMessagePriority = TeamMessagePriority.NORMAL,
) : Message(
    to = to,
    from = from,
    body = body,
) {
    companion object : FromJsonObject<TeamMessage>

    override val type: MessageType = MessageType.TEAM_MESSAGE

}
