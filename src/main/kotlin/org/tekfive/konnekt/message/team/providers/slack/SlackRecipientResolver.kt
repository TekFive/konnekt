package org.tekfive.konnekt.message.team.providers.slack

import org.tekfive.konnekt.message.MessageRecipient

open class SlackRecipientResolver(
    private val client: SlackClient,
) {

    open fun resolveRecipients(recipients: List<MessageRecipient>): SlackResolutionResult {
        val resolved = mutableListOf<SlackResolvedRecipient>()
        val unresolved = mutableListOf<MessageRecipient>()

        for (recipient in recipients) {
            val resolvedRecipient = resolveRecipient(recipient)
            if (resolvedRecipient != null) {
                resolved.add(resolvedRecipient)
            } else {
                unresolved.add(recipient)
            }
        }

        return SlackResolutionResult(resolved, unresolved)
    }

    fun resolveRecipient(recipient: MessageRecipient): SlackResolvedRecipient? {
        val address = recipient.address.trim()
        if (address.isBlank()) return null

        val explicitId = extractExplicitId(address)
        if (explicitId != null) {
            return if (isUserId(explicitId)) {
                client.openConversation(explicitId)?.let { SlackResolvedRecipient(recipient, it) }
            } else {
                SlackResolvedRecipient(recipient, explicitId)
            }
        }

        if (address.contains("@")) {
            val userId = client.lookupUserByEmail(address) ?: return null
            val channelId = client.openConversation(userId) ?: return null
            return SlackResolvedRecipient(recipient, channelId)
        }

        return client.findConversationByName(address)
            ?.let { SlackResolvedRecipient(recipient, it) }
    }

    private fun extractExplicitId(address: String): String? {
        val stripped = address
            .removePrefix("<#")
            .removePrefix("<@")
            .removePrefix("#")
            .removePrefix("@")
            .substringBefore("|")
            .removeSuffix(">")

        return when {
            isConversationId(stripped) -> stripped
            isUserId(stripped) -> stripped
            else -> null
        }
    }

    private fun isConversationId(value: String): Boolean {
        return value.length > 1 && value[0] in setOf('C', 'G', 'D') && value.drop(1).all { it.isLetterOrDigit() }
    }

    private fun isUserId(value: String): Boolean {
        return value.length > 1 && value[0] in setOf('U', 'W') && value.drop(1).all { it.isLetterOrDigit() }
    }
}
