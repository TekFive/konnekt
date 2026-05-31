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

        if (resolution.resolved.isEmpty()) {
            throw TeamMessageException("Slack could not resolve any recipients")
        }

        val text = renderMessage(message)
        val messageIds = resolution.resolved.map { resolved ->
            client.postMessage(resolved.channelId, text).messageId
        }

        return TeamMessageResponse(
            messageId = aggregateMessageIds(messageIds),
            endpointId = "",
            status = TeamMessageStatus.SENT,
        )
    }

    override fun status(messageId: String, config: JsonObject): TeamMessageStatus {
        return TeamMessageStatus.SENT
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
