package org.tekfive.konnekt.message.team.providers.tigerconnect.model

import org.tekfive.jfk.FromJsonObject

data class TigerConnectRoleRecord(
    val id: String? = null,
    val name: String? = null,
) {
    companion object : FromJsonObject<TigerConnectRoleRecord>
}

data class TigerConnectRoleLookupResponse(
    val role: TigerConnectRoleRecord? = null,
    val roles: List<TigerConnectRoleRecord> = emptyList(),
) {
    companion object : FromJsonObject<TigerConnectRoleLookupResponse>
}
