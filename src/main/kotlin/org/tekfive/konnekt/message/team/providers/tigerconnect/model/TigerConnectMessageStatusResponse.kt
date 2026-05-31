package org.tekfive.konnekt.message.team.providers.tigerconnect.model

import org.tekfive.jfk.FromJsonObject

data class TigerConnectMessageStatusResponse(
    val messageId: String? = null,
    val status: String? = null,
) {
    companion object : FromJsonObject<TigerConnectMessageStatusResponse>
}
