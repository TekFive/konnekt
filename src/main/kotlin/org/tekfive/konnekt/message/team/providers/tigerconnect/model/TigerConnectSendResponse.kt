package org.tekfive.konnekt.message.team.providers.tigerconnect.model

import org.tekfive.jfk.FromJsonObject

data class TigerConnectSendResponse(
    val messageId: String? = null,
    val id: String? = null,
    val status: String? = null,
) {
    companion object : FromJsonObject<TigerConnectSendResponse>

    val resolvedMessageId: String?
        get() = messageId ?: id
}
