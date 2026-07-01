package org.tekfive.konnekt.message.team

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

/**
 * Deliberately not a `data class`: generated `equals`/`hashCode` would compare the [content]
 * ByteArray by reference, which is broken equality.
 */
class TeamMessageAttachment(
    val fileName: String,
    val contentType: String,
    val content: ByteArray,
) : ToJsonObject {
    companion object : FromJsonObject<TeamMessageAttachment>
}
