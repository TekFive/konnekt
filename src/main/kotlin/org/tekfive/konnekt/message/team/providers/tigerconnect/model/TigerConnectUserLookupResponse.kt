package org.tekfive.konnekt.message.team.providers.tigerconnect.model

import org.tekfive.jfk.FromJsonObject

data class TigerConnectUserRecord(
    val id: String? = null,
    val email: String? = null,
) {
    companion object : FromJsonObject<TigerConnectUserRecord>
}

data class TigerConnectUserLookupResponse(
    val user: TigerConnectUserRecord? = null,
    val users: List<TigerConnectUserRecord> = emptyList(),
) {
    companion object : FromJsonObject<TigerConnectUserLookupResponse>
}
