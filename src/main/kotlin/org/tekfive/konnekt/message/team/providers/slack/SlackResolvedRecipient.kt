package org.tekfive.konnekt.message.team.providers.slack

import org.tekfive.konnekt.message.MessageRecipient

data class SlackResolvedRecipient(
    val originalRecipient: MessageRecipient,
    val channelId: String,
)

data class SlackResolutionResult(
    val resolved: List<SlackResolvedRecipient>,
    val unresolved: List<MessageRecipient>,
)
