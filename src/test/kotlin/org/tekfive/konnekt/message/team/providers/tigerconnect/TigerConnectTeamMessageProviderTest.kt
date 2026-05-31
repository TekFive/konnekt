package org.tekfive.konnekt.message.team.providers.tigerconnect

import org.tekfive.konnekt.message.team.TeamMessageCapability
import org.tekfive.konnekt.message.team.senders.TigerConnectSender
import kotlin.test.Test
import kotlin.test.assertEquals

class TigerConnectSenderTest {

    @Test
    fun `sender reports expected capabilities`() {
        assertEquals(
            setOf(TeamMessageCapability.PRIORITY, TeamMessageCapability.STATUS_LOOKUP),
            TigerConnectSender.capabilities,
        )
    }
}
