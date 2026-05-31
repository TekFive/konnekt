package org.tekfive.konnekt.message.team.providers.slack

import org.tekfive.konnekt.message.MessageRecipient
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackRecipientResolverTest {

    @Test
    fun `resolver handles channel ids user ids emails channel names and unresolved recipients`() {
        val client = object : SlackClient(SlackAuth("xoxb-token")) {
            override fun lookupUserByEmail(email: String): String? {
                return if (email == "user@example.com") "U111" else null
            }

            override fun openConversation(userId: String): String? {
                return when (userId) {
                    "U111" -> "D111"
                    "U222" -> "D222"
                    else -> null
                }
            }

            override fun findConversationByName(name: String): String? {
                return if (name.removePrefix("#") == "care-team") "C333" else null
            }
        }

        val resolver = SlackRecipientResolver(client)
        val result = resolver.resolveRecipients(
            listOf(
                MessageRecipient("C000"),
                MessageRecipient("<@U222>"),
                MessageRecipient("user@example.com"),
                MessageRecipient("#care-team"),
                MessageRecipient("missing@example.com"),
            )
        )

        assertEquals(listOf("C000", "D222", "D111", "C333"), result.resolved.map { it.channelId })
        assertEquals(listOf("missing@example.com"), result.unresolved.map { it.address })
    }
}
