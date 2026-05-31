package org.tekfive.konnekt.message.sms

import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageType
import org.tekfive.konnekt.message.QueuedMessage

class QueuedSmsMessage(
    val label: String,
    val endpointId: String,
    val description: String? = null,
    val to: List<MessageAddress>,
    val from: MessageAddress,
    val body: String,
    val trackReceipt: Boolean = false,
    val deliverAfter: Long? = null,
    val maxAttempts: Int? = null,
) {

    internal fun toQueuedMessage(): QueuedMessage {
        val smsMessage = SmsMessage(
            to = to,
            from = from,
            body = body,
        )

        return QueuedMessage(
            recipients = to.map { it.address },
            label = label,
            providerTypeConfigurationId = endpointId,
            description = description,
            type = MessageType.SMS,
            trackReceipt = trackReceipt,
            message = smsMessage.toJsonObject(),
            createdAt = System.currentTimeMillis(),
            deliverAfter = deliverAfter,
            maxAttempts = maxAttempts ?: 1,
        )
    }
}
