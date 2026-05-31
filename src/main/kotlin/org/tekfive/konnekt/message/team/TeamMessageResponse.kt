package org.tekfive.konnekt.message.team

data class TeamMessageResponse(
    val messageId: String,
    val endpointId: String,
    val status: TeamMessageStatus,
)
