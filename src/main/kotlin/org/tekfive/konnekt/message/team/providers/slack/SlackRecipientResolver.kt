package org.tekfive.konnekt.message.team.providers.slack

import org.slf4j.LoggerFactory
import org.tekfive.konnekt.message.MessageRecipient
import java.io.IOException

open class SlackRecipientResolver(
    private val client: SlackClient,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    open fun resolveRecipients(recipients: List<MessageRecipient>): SlackResolutionResult {
        val resolved = mutableListOf<SlackResolvedRecipient>()
        val unresolved = mutableListOf<MessageRecipient>()

        for (recipient in recipients) {
            // Isolate per-recipient lookup failures so one bad lookup does not abort the batch.
            val resolvedRecipient = try {
                resolveRecipient(recipient)
            } catch (e: SlackException) {
                log.warn("Slack recipient lookup failed; treating 1 recipient as unresolved", e)
                null
            } catch (e: IOException) {
                log.warn("Slack recipient lookup failed with I/O error; treating 1 recipient as unresolved", e)
                null
            }

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
            val resolvedById = resolveExplicitId(recipient, explicitId)
            if (resolvedById != null) {
                return resolvedById
            }
            // ID lookup failed — fall through to name-based resolution.
        }

        if (address.contains("@")) {
            val userId = client.lookupUserByEmail(address) ?: return null
            val channelId = client.openConversation(userId) ?: return null
            return SlackResolvedRecipient(recipient, channelId)
        }

        return client.findConversationByName(address)
            ?.let { SlackResolvedRecipient(recipient, it) }
    }

    private fun resolveExplicitId(recipient: MessageRecipient, explicitId: String): SlackResolvedRecipient? {
        return try {
            if (isUserId(explicitId)) {
                client.openConversation(explicitId)?.let { SlackResolvedRecipient(recipient, it) }
            } else {
                SlackResolvedRecipient(recipient, explicitId)
            }
        } catch (e: SlackException) {
            log.warn("Slack ID lookup failed for 1 recipient; falling back to name-based resolution", e)
            null
        }
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
        return CONVERSATION_ID_REGEX.matches(value)
    }

    private fun isUserId(value: String): Boolean {
        return USER_ID_REGEX.matches(value)
    }

    companion object {
        // Slack IDs look like C0123ABCD / U0123ABCD: all-uppercase alphanumeric, at least
        // 9 characters. Anything looser misclassifies plain names (e.g. "General") as IDs.
        private val CONVERSATION_ID_REGEX = Regex("^[CGD][A-Z0-9]{8,}$")
        private val USER_ID_REGEX = Regex("^[UW][A-Z0-9]{8,}$")
    }
}
