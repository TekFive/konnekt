package org.tekfive.konnekt.message.team

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

data class TeamMessageAttachment(
    val fileName: String,
    val contentType: String,
    val content: ByteArray,
) : ToJsonObject {
    companion object : FromJsonObject<TeamMessageAttachment>
}
