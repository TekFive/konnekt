package org.tekfive.konnekt.message.team.providers.tigerconnect.model

import org.tekfive.jfk.ToJsonObject

data class TigerConnectSendRequest(
    val targetType: String,
    val targetId: String,
    val subject: String? = null,
    val body: String,
    val priority: String? = null,
) : ToJsonObject
