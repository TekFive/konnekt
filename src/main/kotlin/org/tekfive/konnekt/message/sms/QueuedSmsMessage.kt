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

    init {
        // Fail fast at queue time: the SMS senders deliver to exactly one recipient, so a
        // multi-recipient message would otherwise sit in the queue and fail at send time.
        // Validation messages must never include the address values (persisted/logged).
        require(to.size == 1) { "SMS messages require exactly one recipient, got ${to.size}" }
        requireUsableAddress(to.single().address, "recipient")
        requireUsableAddress(from.address, "from")
    }

    private fun requireUsableAddress(address: String, role: String) {
        require(address.isNotBlank()) { "SMS $role address must not be blank" }
        require(address.trim() == address) { "SMS $role address must not have leading or trailing whitespace" }
    }

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
