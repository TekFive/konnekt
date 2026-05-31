package org.tekfive.konnekt.message.team.providers.tigerconnect

import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectDistributionListLookupResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectDistributionListRecord
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectGroupLookupResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectGroupRecord
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectRoleLookupResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectRoleRecord
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectUserLookupResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectUserRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class TigerConnectRecipientResolverTest {

    @Test
    fun `resolver resolves all supported recipient types and keeps unresolved recipients`() {
        val client = object : TigerConnectClient(TigerConnectAuth("key", "secret")) {
            override fun findUserByEmail(email: String): TigerConnectUserLookupResponse {
                return if (email == "user@example.com") {
                    TigerConnectUserLookupResponse(users = listOf(TigerConnectUserRecord(id = "user-1", email = email)))
                } else {
                    TigerConnectUserLookupResponse()
                }
            }

            override fun findGroupByName(name: String): TigerConnectGroupLookupResponse {
                return if (name == "Care Team") {
                    TigerConnectGroupLookupResponse(groups = listOf(TigerConnectGroupRecord(id = "group-1", name = name)))
                } else {
                    TigerConnectGroupLookupResponse()
                }
            }

            override fun findRoleByName(name: String): TigerConnectRoleLookupResponse {
                return if (name == "On Call") {
                    TigerConnectRoleLookupResponse(roles = listOf(TigerConnectRoleRecord(id = "role-1", name = name)))
                } else {
                    TigerConnectRoleLookupResponse()
                }
            }

            override fun findDistributionListByName(name: String): TigerConnectDistributionListLookupResponse {
                return if (name == "Announcements") {
                    TigerConnectDistributionListLookupResponse(
                        distributionLists = listOf(TigerConnectDistributionListRecord(id = "list-1", name = name))
                    )
                } else {
                    TigerConnectDistributionListLookupResponse()
                }
            }
        }

        val resolver = TigerConnectRecipientResolver(client)
        val result = resolver.resolveRecipients(
            listOf(
                MessageRecipient("user@example.com"),
                MessageRecipient("Care Team"),
                MessageRecipient("On Call"),
                MessageRecipient("Announcements"),
                MessageRecipient("missing"),
            )
        )

        assertEquals(4, result.resolved.size)
        assertEquals(1, result.unresolved.size)
        assertEquals("missing", result.unresolved.single().address)
    }
}
