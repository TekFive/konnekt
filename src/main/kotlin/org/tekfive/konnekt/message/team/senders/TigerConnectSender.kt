package org.tekfive.konnekt.message.team.senders

import org.slf4j.LoggerFactory
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
import org.tekfive.konnekt.message.team.providers.tigerconnect.TigerConnectException
import org.tekfive.konnekt.message.team.providers.tigerconnect.TigerConnectRecipientResolver
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectSendRequest
import java.io.IOException

/**
 * Stateless TigerConnect sender that reads connection credentials from a caller-supplied
 * JsonObject config. Replaces the Ack-configured TigerConnectTeamMessageProviderImpl.
 */
object TigerConnectSender : TeamMessageSender {

    private val log = LoggerFactory.getLogger(TigerConnectSender::class.java)

    override val capabilities: Set<TeamMessageCapability> = setOf(
        TeamMessageCapability.PRIORITY,
        TeamMessageCapability.STATUS_LOOKUP,
    )

    override fun send(message: TeamMessage, config: JsonObject): TeamMessageResponse {
        val auth = buildAuth(config)
        val client = TigerConnectClient(auth)
        val resolver = TigerConnectRecipientResolver(client)
        val resolution = resolver.resolveRecipients(message.to)

        // Fail fast before posting anything: sending to a resolved subset would be silent
        // partial delivery, and receipts would be recorded for recipients never sent to.
        if (resolution.unresolved.isNotEmpty()) {
            throw TeamMessageException(
                "TigerConnect could not resolve ${resolution.unresolved.size} of ${message.to.size} recipients"
            )
        }
        if (resolution.resolved.isEmpty()) {
            throw TeamMessageException("TigerConnect could not resolve any recipients")
        }

        val messageIds = mutableListOf<String>()
        for (resolved in resolution.resolved) {
            val sendRequest = TigerConnectSendRequest(
                targetType = resolved.targetTypeName.lowercase(),
                targetId = resolved.targetId,
                subject = message.subject,
                body = message.body,
                priority = encodePriority(message.priority),
            )
            try {
                val response = client.sendMessage(sendRequest)
                val messageId = response.resolvedMessageId
                    ?: throw TeamMessageException("TigerConnect response did not include a message id")
                messageIds.add(messageId)
            } catch (e: Exception) {
                throw wrapPostFailure(e, sentCount = messageIds.size, totalCount = resolution.resolved.size)
            }
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

        val statuses = mutableListOf<TeamMessageStatus>()
        for (id in ids) {
            // Isolate per-id failures so one bad lookup does not abort the whole status check.
            try {
                statuses.add(mapStatus(client.getMessageStatus(id).status))
            } catch (e: TigerConnectException) {
                log.warn("TigerConnect status lookup failed for 1 of {} message ids", ids.size, e)
                statuses.add(TeamMessageStatus.UNKNOWN)
            } catch (e: IOException) {
                log.warn("TigerConnect status lookup failed with I/O error for 1 of {} message ids", ids.size, e)
                statuses.add(TeamMessageStatus.UNKNOWN)
            }
        }

        return aggregateStatus(statuses)
    }

    private fun wrapPostFailure(cause: Exception, sentCount: Int, totalCount: Int): TeamMessageException {
        if (sentCount == 0) {
            // Nothing was delivered yet — let the original exception drive retry classification.
            throw cause
        }
        // Some recipients already received the message; a retry would duplicate delivery to them.
        return TeamMessageException(
            "TigerConnect send failed after sending to $sentCount of $totalCount recipients",
            recoverable = false,
            cause = cause,
        )
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
            else -> TeamMessageStatus.UNKNOWN
        }
    }

    /**
     * Any failed delivery makes the aggregate FAILED so failures are never masked; READ and
     * DELIVERED are only reported when every recipient has reached at least that state.
     */
    private fun aggregateStatus(statuses: List<TeamMessageStatus>): TeamMessageStatus {
        return when {
            statuses.any { it == TeamMessageStatus.FAILED } -> TeamMessageStatus.FAILED
            statuses.isNotEmpty() && statuses.all { it == TeamMessageStatus.READ } -> TeamMessageStatus.READ
            statuses.isNotEmpty() && statuses.all { it == TeamMessageStatus.DELIVERED || it == TeamMessageStatus.READ } ->
                TeamMessageStatus.DELIVERED
            else -> TeamMessageStatus.SENT
        }
    }
}
