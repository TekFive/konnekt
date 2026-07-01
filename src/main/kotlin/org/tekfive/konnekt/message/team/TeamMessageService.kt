package org.tekfive.konnekt.message.team

import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessageReceiptDetails
import org.tekfive.konnekt.message.MessagingException
import org.tekfive.konnekt.message.QueuedMessage
import org.tekfive.konnekt.message.QueuedMessageTable
import org.tekfive.konnekt.message.team.providers.slack.SlackException
import org.tekfive.konnekt.message.team.providers.tigerconnect.TigerConnectException
import org.tekfive.konnekt.message.team.senders.SlackSender
import org.tekfive.konnekt.message.team.senders.TigerConnectSender
import java.io.IOException

/**
 * Central team-message service that dispatches to endpoint-specific senders,
 * validates requests against sender capabilities, and manages the queue path.
 */
object TeamMessageService {

    @Volatile
    private var resolver: TeamMessageEndpointResolver? = null

    @Synchronized
    fun registerResolver(resolver: TeamMessageEndpointResolver) {
        this.resolver = resolver
    }

    fun send(message: TeamMessage, endpoint: TeamMessageEndpoint): TeamMessageResponse {
        return dispatch(message, endpoint)
    }

    fun queue(message: QueuedTeamMessage): Long {
        validateQueuedCapabilities(message)
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
        try {
            return sender.send(message, endpoint.config)
        } catch (e: Exception) {
            throw classifyForRetry(e)
        }
    }

    private fun dispatchStatus(messageId: String, endpoint: TeamMessageEndpoint): TeamMessageStatus {
        val sender = senderFor(endpoint.provider)
        if (!sender.capabilities.contains(TeamMessageCapability.STATUS_LOOKUP)) {
            return TeamMessageStatus.UNKNOWN
        }
        try {
            return sender.status(messageId, endpoint.config)
        } catch (e: Exception) {
            throw classifyForRetry(e)
        }
    }

    /**
     * Wraps provider exceptions as [MessagingException] so the queue's retry machinery can
     * classify them: I/O failures are recoverable, HTTP failures are recoverable per
     * [MessagingException.isRecoverableStatus] (408/429/5xx — this covers Slack 429 rate
     * limiting; the queue applies its own retry delay), and everything else is not.
     *
     * Wrapper messages carry only status codes and the provider exception's already-scrubbed
     * message — never response bodies, recipient addresses, message content, or credentials.
     */
    private fun classifyForRetry(e: Exception): MessagingException {
        if (e is MessagingException) {
            return e
        }
        if (e is IOException) {
            return MessagingException(true, "Team message provider call failed with an I/O error", e)
        }

        val statusCode = when (e) {
            is SlackException -> e.statusCode
            is TigerConnectException -> e.statusCode
            else -> null
        }
        if (statusCode != null) {
            return MessagingException(
                MessagingException.isRecoverableStatus(statusCode),
                "Team message provider call failed with HTTP status $statusCode",
                e,
            )
        }

        if (e is SlackException || e is TigerConnectException) {
            // Provider exception messages are scrubbed at the source (status/error tokens only).
            return MessagingException(false, "Team message provider call failed: ${e.message}", e)
        }
        return MessagingException(false, "Team message provider call failed: ${e.javaClass.simpleName}", e)
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

    /**
     * Best-effort queue-time capability check so unsupported attachments/priorities fail at
     * enqueue instead of at delivery. Skipped when the endpoint cannot be resolved yet — the
     * same validation always runs again at dispatch time.
     */
    private fun validateQueuedCapabilities(message: QueuedTeamMessage) {
        val endpoint = resolver?.resolve(message.endpointId) ?: return
        val sender = senderFor(endpoint.provider)
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
