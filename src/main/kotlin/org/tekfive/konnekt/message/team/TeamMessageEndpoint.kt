package org.tekfive.konnekt.message.team

import org.tekfive.jfk.JsonObject

data class TeamMessageEndpoint(
    val id: String,
    val provider: TeamMessageServiceProvider,
    val config: JsonObject,
)
