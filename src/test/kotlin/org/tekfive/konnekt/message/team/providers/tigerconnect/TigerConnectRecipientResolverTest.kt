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
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `failed lookup continues to the next lookup type in the fallback chain`() {
        val client = object : TigerConnectClient(TigerConnectAuth("key", "secret")) {
            override fun findUserByEmail(email: String): TigerConnectUserLookupResponse {
                throw TigerConnectException("TigerConnect request failed with HTTP status 404", statusCode = 404)
            }

            override fun findGroupByName(name: String): TigerConnectGroupLookupResponse {
                return TigerConnectGroupLookupResponse(
                    groups = listOf(TigerConnectGroupRecord(id = "group-1", name = name))
                )
            }

            override fun findRoleByName(name: String): TigerConnectRoleLookupResponse {
                return TigerConnectRoleLookupResponse()
            }

            override fun findDistributionListByName(name: String): TigerConnectDistributionListLookupResponse {
                return TigerConnectDistributionListLookupResponse()
            }
        }

        val resolver = TigerConnectRecipientResolver(client)
        val resolved = resolver.resolveRecipient(MessageRecipient("Care Team"))

        assertEquals("group-1", resolved?.targetId)
        assertEquals("group", resolved?.targetTypeName)
    }

    @Test
    fun `lookup failure for one recipient does not abort the batch`() {
        val client = object : TigerConnectClient(TigerConnectAuth("key", "secret")) {
            override fun findUserByEmail(email: String): TigerConnectUserLookupResponse {
                if (email == "broken@example.com") {
                    throw IOException("connection reset")
                }
                return TigerConnectUserLookupResponse(
                    users = listOf(TigerConnectUserRecord(id = "user-1", email = email))
                )
            }
        }

        val resolver = TigerConnectRecipientResolver(client)
        val result = resolver.resolveRecipients(
            listOf(
                MessageRecipient("broken@example.com"),
                MessageRecipient("user@example.com"),
            )
        )

        assertEquals(listOf("user-1"), result.resolved.map { it.targetId })
        assertEquals(listOf("broken@example.com"), result.unresolved.map { it.address })
    }

    @Test
    fun `name matching is exact and case-insensitive`() {
        val client = object : TigerConnectClient(TigerConnectAuth("key", "secret")) {
            override fun findUserByEmail(email: String): TigerConnectUserLookupResponse {
                return TigerConnectUserLookupResponse()
            }

            override fun findGroupByName(name: String): TigerConnectGroupLookupResponse {
                // Simulates a fuzzy provider search returning near-matches first.
                return TigerConnectGroupLookupResponse(
                    groups = listOf(
                        TigerConnectGroupRecord(id = "group-extra", name = "Care Team Extra"),
                        TigerConnectGroupRecord(id = "group-1", name = "care team"),
                    )
                )
            }

            override fun findRoleByName(name: String): TigerConnectRoleLookupResponse {
                return TigerConnectRoleLookupResponse()
            }

            override fun findDistributionListByName(name: String): TigerConnectDistributionListLookupResponse {
                return TigerConnectDistributionListLookupResponse()
            }
        }

        val resolver = TigerConnectRecipientResolver(client)
        val resolved = resolver.resolveRecipient(MessageRecipient("Care Team"))

        assertEquals("group-1", resolved?.targetId)
    }

    @Test
    fun `multiple exact name matches are ambiguous and left unresolved`() {
        val client = object : TigerConnectClient(TigerConnectAuth("key", "secret")) {
            override fun findUserByEmail(email: String): TigerConnectUserLookupResponse {
                return TigerConnectUserLookupResponse()
            }

            override fun findGroupByName(name: String): TigerConnectGroupLookupResponse {
                return TigerConnectGroupLookupResponse(
                    groups = listOf(
                        TigerConnectGroupRecord(id = "group-1", name = "Care Team"),
                        TigerConnectGroupRecord(id = "group-2", name = "care team"),
                    )
                )
            }

            override fun findRoleByName(name: String): TigerConnectRoleLookupResponse {
                return TigerConnectRoleLookupResponse()
            }

            override fun findDistributionListByName(name: String): TigerConnectDistributionListLookupResponse {
                return TigerConnectDistributionListLookupResponse()
            }
        }

        val resolver = TigerConnectRecipientResolver(client)

        assertNull(resolver.resolveRecipient(MessageRecipient("Care Team")))
    }
}
