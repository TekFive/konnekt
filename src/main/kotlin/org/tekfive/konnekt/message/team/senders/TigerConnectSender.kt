package org.tekfive.konnekt.message.team.senders

import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.message.team.TeamMessage
import org.tekfive.konnekt.message.team.TeamMessageCapability
import org.tekfive.konnekt.message.team.TeamMessageException
import org.tekfive.konnekt.message.team.TeamMessagePriority
import org.tekfive.konnekt.message.team.TeamMessageResponse
import org.tekfive.konnekt.message.team.TeamMessageSender
import org.tekfive.konnekt.message.team.TeamMessageStatus
import org.tekfive.konnekt.message.team.providers.tigerconnect.TigerConnectAuth
import org.tekfive.konnekt.message.team.providers.tigerconnect.TigerConnectClient
import org.tekfive.konnekt.message.team.providers.tigerconnect.TigerConnectRecipientResolver
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectSendRequest

/**
 * Stateless TigerConnect sender that reads connection credentials from a caller-supplied
 * JsonObject config. Replaces the Ack-configured TigerConnectTeamMessageProviderImpl.
 */
object TigerConnectSender : TeamMessageSender {

    override val capabilities: Set<TeamMessageCapability> = setOf(
        TeamMessageCapability.PRIORITY,
        TeamMessageCapability.STATUS_LOOKUP,
    )

    override fun send(message: TeamMessage, config: JsonObject): TeamMessageResponse {
        val auth = buildAuth(config)
        val client = TigerConnectClient(auth)
        val resolver = TigerConnectRecipientResolver(client)
        val resolution = resolver.resolveRecipients(message.to)

        if (resolution.resolved.isEmpty()) {
            throw TeamMessageException("TigerConnect could not resolve any recipients")
        }

        val messageIds = resolution.resolved.map { resolved ->
            val sendRequest = TigerConnectSendRequest(
                targetType = resolved.targetTypeName.lowercase(),
                targetId = resolved.targetId,
                subject = message.subject,
                body = message.body,
                priority = encodePriority(message.priority),
            )
            val response = client.sendMessage(sendRequest)
            response.resolvedMessageId ?: throw TeamMessageException("TigerConnect response did not include a message id")
        }

        val aggregateMessageId = if (messageIds.size == 1) {
            messageIds.single()
        } else {
            "multi:${messageIds.joinToString(",")}"
        }

        return TeamMessageResponse(
            messageId = aggregateMessageId,
            endpointId = "",
            status = TeamMessageStatus.SENT,
        )
    }

    override fun status(messageId: String, config: JsonObject): TeamMessageStatus {
        val auth = buildAuth(config)
        val client = TigerConnectClient(auth)
        val ids = if (messageId.startsWith("multi:")) {
            messageId.removePrefix("multi:").split(",").filter { it.isNotBlank() }
        } else {
            listOf(messageId)
        }
        val statuses = ids.map { mapStatus(client.getMessageStatus(it).status) }
        return aggregateStatus(statuses)
    }

    private fun buildAuth(config: JsonObject): TigerConnectAuth {
        val apiKey = config["apiKey"].string
            ?: error("TigerConnect apiKey is required in endpoint config.")
        val apiSecret = config["apiSecret"].string
            ?: error("TigerConnect apiSecret is required in endpoint config.")
        val baseUrl = config["baseUrl"].string ?: TigerConnectAuth.DEFAULT_BASE_URL
        return TigerConnectAuth(apiKey = apiKey, apiSecret = apiSecret, baseUrl = baseUrl)
    }

    private fun encodePriority(priority: TeamMessagePriority): String? {
        return when (priority) {
            TeamMessagePriority.NORMAL -> null
            TeamMessagePriority.HIGH -> "high"
            TeamMessagePriority.URGENT -> "urgent"
        }
    }

    private fun mapStatus(status: String?): TeamMessageStatus {
        return when (status?.lowercase()) {
            "queued", "pending" -> TeamMessageStatus.QUEUED
            "sent" -> TeamMessageStatus.SENT
            "delivered" -> TeamMessageStatus.DELIVERED
            "read" -> TeamMessageStatus.READ
            "failed", "error" -> TeamMessageStatus.FAILED
            else -> TeamMessageStatus.SENT
        }
    }

    private fun aggregateStatus(statuses: List<TeamMessageStatus>): TeamMessageStatus {
        return when {
            statuses.any { it == TeamMessageStatus.READ } -> TeamMessageStatus.READ
            statuses.any { it == TeamMessageStatus.DELIVERED } -> TeamMessageStatus.DELIVERED
            statuses.any { it == TeamMessageStatus.SENT } -> TeamMessageStatus.SENT
            statuses.any { it == TeamMessageStatus.QUEUED } -> TeamMessageStatus.QUEUED
            else -> TeamMessageStatus.FAILED
        }
    }
}
