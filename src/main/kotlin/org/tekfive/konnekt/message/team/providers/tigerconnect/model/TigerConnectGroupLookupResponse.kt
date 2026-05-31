package org.tekfive.konnekt.message.team.providers.tigerconnect.model

import org.tekfive.jfk.FromJsonObject

data class TigerConnectGroupRecord(
    val id: String? = null,
    val name: String? = null,
) {
    companion object : FromJsonObject<TigerConnectGroupRecord>
}

data class TigerConnectGroupLookupResponse(
    val group: TigerConnectGroupRecord? = null,
    val groups: List<TigerConnectGroupRecord> = emptyList(),
) {
    companion object : FromJsonObject<TigerConnectGroupLookupResponse>
}
