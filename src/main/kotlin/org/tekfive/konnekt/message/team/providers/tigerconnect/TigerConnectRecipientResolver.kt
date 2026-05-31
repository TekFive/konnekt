package org.tekfive.konnekt.message.team.providers.tigerconnect

import org.tekfive.konnekt.message.MessageRecipient

open class TigerConnectRecipientResolver(
    private val client: TigerConnectClient,
) {

    open fun resolveRecipients(recipients: List<MessageRecipient>): TigerConnectResolutionResult {
        val resolved = mutableListOf<TigerConnectResolvedRecipient>()
        val unresolved = mutableListOf<MessageRecipient>()

        for (recipient in recipients) {
            val resolvedRecipient = resolveRecipient(recipient)

            if (resolvedRecipient != null) {
                resolved.add(resolvedRecipient)
            } else {
                unresolved.add(recipient)
            }
        }

        return TigerConnectResolutionResult(resolved, unresolved)
    }

    fun resolveRecipient(recipient: MessageRecipient): TigerConnectResolvedRecipient? {
        var resolved = resolveUser(recipient)
        if (resolved == null) {
            resolved = resolveGroup(recipient)
            if (resolved == null) {
                resolved = resolveRole(recipient)
                if (resolved == null) {
                    resolved = resolveDistributionList(recipient)
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
        val id = response.group?.id ?: response.groups.firstOrNull()?.id
        return id?.let { TigerConnectResolvedRecipient(recipient, "group", it) }
    }

    protected open fun resolveRole(recipient: MessageRecipient): TigerConnectResolvedRecipient? {
        val response = client.findRoleByName(recipient.address)
        val id = response.role?.id ?: response.roles.firstOrNull()?.id
        return id?.let { TigerConnectResolvedRecipient(recipient, "role", it) }
    }

    protected open fun resolveDistributionList(recipient: MessageRecipient): TigerConnectResolvedRecipient? {
        val response = client.findDistributionListByName(recipient.address)
        val id = response.distributionList?.id ?: response.distributionLists.firstOrNull()?.id
        return id?.let { TigerConnectResolvedRecipient(recipient, "distribution_type", it) }
    }
}
