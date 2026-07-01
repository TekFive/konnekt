package org.tekfive.konnekt.message.team.senders

import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.message.team.TeamMessage
import org.tekfive.konnekt.message.team.TeamMessageCapability
import org.tekfive.konnekt.message.team.TeamMessageException
import org.tekfive.konnekt.message.team.TeamMessageResponse
import org.tekfive.konnekt.message.team.TeamMessageSender
import org.tekfive.konnekt.message.team.TeamMessageStatus
import org.tekfive.konnekt.message.team.providers.slack.SlackAuth
import org.tekfive.konnekt.message.team.providers.slack.SlackClient
import org.tekfive.konnekt.message.team.providers.slack.SlackRecipientResolver

object SlackSender : TeamMessageSender {

    override val capabilities: Set<TeamMessageCapability> = emptySet()

    override fun send(message: TeamMessage, config: JsonObject): TeamMessageResponse {
        val auth = buildAuth(config)
        val client = SlackClient(auth)
        val resolver = SlackRecipientResolver(client)
        val resolution = resolver.resolveRecipients(message.to)

        // Fail fast before posting anything: sending to a resolved subset would be silent
        // partial delivery, and receipts would be recorded for recipients never sent to.
        if (resolution.unresolved.isNotEmpty()) {
            throw TeamMessageException(
                "Slack could not resolve ${resolution.unresolved.size} of ${message.to.size} recipients"
            )
        }
        if (resolution.resolved.isEmpty()) {
            throw TeamMessageException("Slack could not resolve any recipients")
        }

        val text = renderMessage(message)
        val messageIds = mutableListOf<String>()
        for (resolved in resolution.resolved) {
            try {
                messageIds.add(client.postMessage(resolved.channelId, text).messageId)
            } catch (e: Exception) {
                throw wrapPostFailure(e, sentCount = messageIds.size, totalCount = resolution.resolved.size)
            }
        }

        return TeamMessageResponse(
            messageId = aggregateMessageIds(messageIds),
            endpointId = "",
            status = TeamMessageStatus.SENT,
        )
    }

    override fun status(messageId: String, config: JsonObject): TeamMessageStatus {
        // Slack offers no message status lookup; SlackSender does not declare STATUS_LOOKUP,
        // so report an honest UNKNOWN rather than fabricating SENT.
        return TeamMessageStatus.UNKNOWN
    }

    private fun wrapPostFailure(cause: Exception, sentCount: Int, totalCount: Int): TeamMessageException {
        if (sentCount == 0) {
            // Nothing was delivered yet — let the original exception drive retry classification.
            throw cause
        }
        // Some recipients already received the message; a retry would duplicate delivery to them.
        return TeamMessageException(
            "Slack post failed after sending to $sentCount of $totalCount recipients",
            recoverable = false,
            cause = cause,
        )
    }

    private fun buildAuth(config: JsonObject): SlackAuth {
        val botToken = config["botToken"].string
            ?: config["token"].string
            ?: error("Slack botToken is required in endpoint config.")
        val baseUrl = config["baseUrl"].string ?: SlackAuth.DEFAULT_BASE_URL
        return SlackAuth(botToken = botToken, baseUrl = baseUrl)
    }

    private fun renderMessage(message: TeamMessage): String {
        val subject = message.subject?.takeIf { it.isNotBlank() }
        return if (subject == null) {
            message.body
        } else {
            "*$subject*\n\n${message.body}"
        }
    }

    private fun aggregateMessageIds(messageIds: List<String>): String {
        return if (messageIds.size == 1) {
            messageIds.single()
        } else {
            "multi:${messageIds.joinToString(",")}"
        }
    }
}
