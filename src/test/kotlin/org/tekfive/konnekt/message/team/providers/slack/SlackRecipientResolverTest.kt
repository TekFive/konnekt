package org.tekfive.konnekt.message.team.providers.slack

import org.tekfive.konnekt.message.MessageRecipient
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackRecipientResolverTest {

    @Test
    fun `resolver handles channel ids user ids emails channel names and unresolved recipients`() {
        val client = object : SlackClient(SlackAuth("xoxb-token")) {
            override fun lookupUserByEmail(email: String): String? {
                return if (email == "user@example.com") "U0AAAA0001" else null
            }

            override fun openConversation(userId: String): String? {
                return when (userId) {
                    "U0AAAA0001" -> "D0AAAA0001"
                    "U0BBBB0002" -> "D0BBBB0002"
                    else -> null
                }
            }

            override fun findConversationByName(name: String): String? {
                return if (name.removePrefix("#").lowercase() == "care-team") "C0CCCC0003" else null
            }
        }

        val resolver = SlackRecipientResolver(client)
        val result = resolver.resolveRecipients(
            listOf(
                MessageRecipient("C0123ABCD"),
                MessageRecipient("<@U0BBBB0002>"),
                MessageRecipient("user@example.com"),
                MessageRecipient("#care-team"),
                MessageRecipient("missing@example.com"),
            )
        )

        assertEquals(
            listOf("C0123ABCD", "D0BBBB0002", "D0AAAA0001", "C0CCCC0003"),
            result.resolved.map { it.channelId },
        )
        assertEquals(listOf("missing@example.com"), result.unresolved.map { it.address })
    }

    @Test
    fun `plain names are resolved by name lookup rather than treated as slack ids`() {
        val lookedUpNames = mutableListOf<String>()
        val client = object : SlackClient(SlackAuth("xoxb-token")) {
            override fun openConversation(userId: String): String? {
                error("ID lookup should not be attempted for plain names")
            }

            override fun findConversationByName(name: String): String? {
                lookedUpNames.add(name)
                return when (name.lowercase()) {
                    "general" -> "C0GENERAL1"
                    "urgent" -> "C0URGENT01"
                    else -> null
                }
            }
        }

        val resolver = SlackRecipientResolver(client)
        val result = resolver.resolveRecipients(
            listOf(
                MessageRecipient("General"),
                MessageRecipient("Urgent"),
                // Too short to be a Slack ID — must be tried as a name, not an ID.
                MessageRecipient("C000"),
            )
        )

        assertEquals(listOf("C0GENERAL1", "C0URGENT01"), result.resolved.map { it.channelId })
        assertEquals(listOf("C000"), result.unresolved.map { it.address })
        assertEquals(listOf("General", "Urgent", "C000"), lookedUpNames)
    }

    @Test
    fun `lookup failure for one recipient does not abort the batch`() {
        val client = object : SlackClient(SlackAuth("xoxb-token")) {
            override fun lookupUserByEmail(email: String): String? {
                if (email == "broken@example.com") {
                    throw SlackException("Slack request failed with HTTP status 500", statusCode = 500)
                }
                return "U0AAAA0001"
            }

            override fun openConversation(userId: String): String? {
                return "D0AAAA0001"
            }
        }

        val resolver = SlackRecipientResolver(client)
        val result = resolver.resolveRecipients(
            listOf(
                MessageRecipient("broken@example.com"),
                MessageRecipient("user@example.com"),
            )
        )

        assertEquals(listOf("D0AAAA0001"), result.resolved.map { it.channelId })
        assertEquals(listOf("broken@example.com"), result.unresolved.map { it.address })
    }

    @Test
    fun `failed user id lookup falls back to name based resolution`() {
        val client = object : SlackClient(SlackAuth("xoxb-token")) {
            override fun openConversation(userId: String): String? {
                throw SlackException("Slack request failed: internal_error")
            }

            override fun findConversationByName(name: String): String? {
                return if (name == "U0BBBB0002") "C0FALLBACK" else null
            }
        }

        val resolver = SlackRecipientResolver(client)
        val result = resolver.resolveRecipients(listOf(MessageRecipient("U0BBBB0002")))

        assertEquals(listOf("C0FALLBACK"), result.resolved.map { it.channelId })
    }
}
