package org.tekfive.konnekt.message.team.providers.tigerconnect

import org.slf4j.LoggerFactory
import org.tekfive.konnekt.message.MessageRecipient
import java.io.IOException

open class TigerConnectRecipientResolver(
    private val client: TigerConnectClient,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    open fun resolveRecipients(recipients: List<MessageRecipient>): TigerConnectResolutionResult {
        val resolved = mutableListOf<TigerConnectResolvedRecipient>()
        val unresolved = mutableListOf<MessageRecipient>()

        for (recipient in recipients) {
            // Isolate per-recipient lookup failures so one bad lookup does not abort the batch.
            val resolvedRecipient = try {
                resolveRecipient(recipient)
            } catch (e: TigerConnectException) {
                log.warn("TigerConnect recipient lookup failed; treating 1 recipient as unresolved", e)
                null
            } catch (e: IOException) {
                log.warn("TigerConnect recipient lookup failed with I/O error; treating 1 recipient as unresolved", e)
                null
            }

            if (resolvedRecipient != null) {
                resolved.add(resolvedRecipient)
            } else {
                unresolved.add(recipient)
            }
        }

        return TigerConnectResolutionResult(resolved, unresolved)
    }

    fun resolveRecipient(recipient: MessageRecipient): TigerConnectResolvedRecipient? {
        var resolved = lookupOrNull("user") { resolveUser(recipient) }
        if (resolved == null) {
            resolved = lookupOrNull("group") { resolveGroup(recipient) }
            if (resolved == null) {
                resolved = lookupOrNull("role") { resolveRole(recipient) }
                if (resolved == null) {
                    resolved = lookupOrNull("distribution list") { resolveDistributionList(recipient) }
                }
            }
        }
        return resolved
    }

    protected open fun resolveUser(recipient: MessageRecipient): TigerConnectResolvedRecipient? {
        val response = client.findUserByEmail(recipient.address)
        val id = response.user?.id ?: response.users.firstOrNull()?.id
        return id?.let { TigerConnectResolvedRecipient(recipient, "user", it) }
    }

    protected open fun resolveGroup(recipient: MessageRecipient): TigerConnectResolvedRecipient? {
        val response = client.findGroupByName(recipient.address)
        val candidates = buildList {
            response.group?.let { add(it.name to it.id) }
            for (group in response.groups) {
                add(group.name to group.id)
            }
        }
        val id = exactMatchId(recipient.address, candidates)
        return id?.let { TigerConnectResolvedRecipient(recipient, "group", it) }
    }

    protected open fun resolveRole(recipient: MessageRecipient): TigerConnectResolvedRecipient? {
        val response = client.findRoleByName(recipient.address)
        val candidates = buildList {
            response.role?.let { add(it.name to it.id) }
            for (role in response.roles) {
                add(role.name to role.id)
            }
        }
        val id = exactMatchId(recipient.address, candidates)
        return id?.let { TigerConnectResolvedRecipient(recipient, "role", it) }
    }

    protected open fun resolveDistributionList(recipient: MessageRecipient): TigerConnectResolvedRecipient? {
        val response = client.findDistributionListByName(recipient.address)
        val candidates = buildList {
            response.distributionList?.let { add(it.name to it.id) }
            for (list in response.distributionLists) {
                add(list.name to list.id)
            }
        }
        val id = exactMatchId(recipient.address, candidates)
        // TODO: "distribution_type" is inconsistent with the sibling target types
        // ("user"/"group"/"role") and looks like a typo for "distribution_list", but the value
        // could not be verified against the TigerConnect API docs. Verify before changing.
        return id?.let { TigerConnectResolvedRecipient(recipient, "distribution_type", it) }
    }

    /**
     * A not-found or 4xx from an earlier lookup in the user → group → role → distribution-list
     * fallback chain must continue to the next lookup type rather than abort the recipient.
     */
    private fun lookupOrNull(
        lookupType: String,
        lookup: () -> TigerConnectResolvedRecipient?,
    ): TigerConnectResolvedRecipient? {
        return try {
            lookup()
        } catch (e: TigerConnectException) {
            log.warn("TigerConnect {} lookup failed; continuing to next lookup type", lookupType, e)
            null
        }
    }

    /**
     * Returns the id of the single candidate whose name exactly matches the requested address
     * (case-insensitive). Multiple distinct matches are ambiguous and resolve to null.
     */
    private fun exactMatchId(requestedName: String, candidates: List<Pair<String?, String?>>): String? {
        val matchingIds = mutableListOf<String>()
        for ((name, id) in candidates) {
            if (id == null || name == null) continue
            if (name.equals(requestedName, ignoreCase = true) && id !in matchingIds) {
                matchingIds.add(id)
            }
        }

        if (matchingIds.size > 1) {
            log.warn(
                "TigerConnect lookup returned {} exact name matches; treating recipient as unresolved",
                matchingIds.size,
            )
            return null
        }
        return matchingIds.firstOrNull()
    }
}
