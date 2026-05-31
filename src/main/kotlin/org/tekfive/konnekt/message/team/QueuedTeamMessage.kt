package org.tekfive.konnekt.message.team

import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.MessageType
import org.tekfive.konnekt.message.QueuedMessage

class QueuedTeamMessage(
    val label: String,
    val endpointId: String,
    val description: String? = null,
    val to: List<MessageRecipient>,
    val from: MessageAddress,
    val subject: String? = null,
    val body: String,
    val attachments: List<TeamMessageAttachment> = emptyList(),
    val priority: TeamMessagePriority = TeamMessagePriority.NORMAL,
    val trackReceipt: Boolean = false,
    val deliverAfter: Long? = null,
    val maxAttempts: Int? = null,
) {

    internal fun toQueuedMessage(): QueuedMessage {
        val secureMessage = TeamMessage(
            to = to,
            from = from,
            subject = subject,
            body = body,
            attachments = attachments,
            priority = priority,
        )

        return QueuedMessage(
            recipients = to.map { it.address },
            label = label,
            providerTypeConfigurationId = endpointId,
            description = description,
            type = MessageType.TEAM_MESSAGE,
            trackReceipt = trackReceipt,
            message = secureMessage.toJsonObject(),
            createdAt = System.currentTimeMillis(),
            deliverAfter = deliverAfter,
            maxAttempts = maxAttempts ?: 1,
        )
    }
}
