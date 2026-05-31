package org.tekfive.konnekt.message.team.providers.slack

data class SlackPostMessageResponse(
    val channel: String,
    val ts: String,
) {
    val messageId: String
        get() = "$channel:$ts"
}
