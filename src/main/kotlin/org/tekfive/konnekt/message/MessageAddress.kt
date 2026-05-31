package org.tekfive.konnekt.message

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

data class MessageAddress(
    val address: String,
    val displayName: String? = null
) : ToJsonObject {
    companion object : FromJsonObject<MessageAddress>
}