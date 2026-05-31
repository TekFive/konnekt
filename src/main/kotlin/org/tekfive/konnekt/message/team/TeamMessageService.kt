package org.tekfive.konnekt.message.team

import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessageReceiptDetails
import org.tekfive.konnekt.message.QueuedMessage
import org.tekfive.konnekt.message.QueuedMessageTable
import org.tekfive.konnekt.message.team.senders.SlackSender
import org.tekfive.konnekt.message.team.senders.TigerConnectSender

/**
 * Central team-message service that dispatches to endpoint-specific senders,
 * validates requests against sender capabilities, and manages the queue path.
 */
object TeamMessageService {

    private var resolver: TeamMessageEndpointResolver? = null

    @Synchronized
    fun registerResolver(resolver: TeamMessageEndpointResolver) {
        this.resolver = resolver
    }

    fun send(message: TeamMessage, endpoint: TeamMessageEndpoint): TeamMessageResponse {
        return dispatch(message, endpoint)
    }

    fun queue(message: QueuedTeamMessage): Long {
        return QueuedMessageTable.create(message.toQueuedMessage()).id
    }

    fun status(messageId: String, endpoint: TeamMessageEndpoint): TeamMessageStatus {
        return dispatchStatus(messageId, endpoint)
    }

    internal fun send(queuedMessage: QueuedMessage): MessageReceiptDetails? {
        val endpoint = resolveEndpoint(queuedMessage.providerTypeConfigurationId)
        val secureMessage = TeamMessage.fromJson(queuedMessage.message)
        val response = dispatch(secureMessage, endpoint)

        if (!queuedMessage.trackReceipt) return null

        val sender = senderFor(endpoint.provider)
        if (!sender.capabilities.contains(TeamMessageCapability.STATUS_LOOKUP)) return null

        return MessageReceiptDetails(
            endpointId = endpoint.id,
            recipientAddresses = secureMessage.to.map { it.address },
            providerTrackingData = json {
                "endpointId" set endpoint.id
                "messageId" set response.messageId
            }
        )
    }

    internal fun reset() {
        resolver = null
    }

    private fun dispatch(message: TeamMessage, endpoint: TeamMessageEndpoint): TeamMessageResponse {
        val sender = senderFor(endpoint.provider)
        validateCapabilities(sender, message)
        return sender.send(message, endpoint.config)
    }

    private fun dispatchStatus(messageId: String, endpoint: TeamMessageEndpoint): TeamMessageStatus {
        val sender = senderFor(endpoint.provider)
        return sender.status(messageId, endpoint.config)
    }

    private fun senderFor(provider: TeamMessageServiceProvider): TeamMessageSender {
        return when (provider) {
            TeamMessageServiceProvider.TIGER_CONNECT -> TigerConnectSender
            TeamMessageServiceProvider.SLACK -> SlackSender
        }
    }

    private fun resolveEndpoint(endpointId: String): TeamMessageEndpoint {
        val r = resolver ?: error("No TeamMessageEndpointResolver registered.")
        return r.resolve(endpointId) ?: error("TeamMessageEndpoint not found for id: $endpointId")
    }

    private fun validateCapabilities(sender: TeamMessageSender, message: TeamMessage) {
        if (message.attachments.isNotEmpty()) {
            requireCapability(sender, TeamMessageCapability.ATTACHMENTS)
        }
        if (message.priority != TeamMessagePriority.NORMAL) {
            requireCapability(sender, TeamMessageCapability.PRIORITY)
        }
    }

    private fun requireCapability(sender: TeamMessageSender, capability: TeamMessageCapability) {
        if (!sender.capabilities.contains(capability)) {
            throw TeamMessageException(
                "Sender does not support capability ${capability.displayName}"
            )
        }
    }
}
