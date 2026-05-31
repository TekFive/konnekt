package org.tekfive.konnekt.message.team.providers.slack

import org.tekfive.konnekt.message.team.TeamMessageCapability
import org.tekfive.konnekt.message.team.senders.SlackSender
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackSenderTest {

    @Test
    fun `sender reports no optional capabilities`() {
        assertEquals(emptySet<TeamMessageCapability>(), SlackSender.capabilities)
    }
}
