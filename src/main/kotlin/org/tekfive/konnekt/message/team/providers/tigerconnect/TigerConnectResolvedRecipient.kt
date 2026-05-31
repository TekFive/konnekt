package org.tekfive.konnekt.message.team.providers.tigerconnect

import org.tekfive.konnekt.message.MessageRecipient

data class TigerConnectResolvedRecipient(
    val originalRecipient: MessageRecipient,
    val targetTypeName: String,
    val targetId: String,
)

data class TigerConnectResolutionResult(
    val resolved: List<TigerConnectResolvedRecipient>,
    val unresolved: List<MessageRecipient>,
)
