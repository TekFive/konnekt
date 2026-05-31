package org.tekfive.konnekt.message.team.providers.tigerconnect.model

import org.tekfive.jfk.FromJsonObject

data class TigerConnectDistributionListRecord(
    val id: String? = null,
    val name: String? = null,
) {
    companion object : FromJsonObject<TigerConnectDistributionListRecord>
}

data class TigerConnectDistributionListLookupResponse(
    val distributionList: TigerConnectDistributionListRecord? = null,
    val distributionLists: List<TigerConnectDistributionListRecord> = emptyList(),
) {
    companion object : FromJsonObject<TigerConnectDistributionListLookupResponse>
}
