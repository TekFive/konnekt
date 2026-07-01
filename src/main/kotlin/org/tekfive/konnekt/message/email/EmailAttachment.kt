package org.tekfive.konnekt.message.email

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

class EmailAttachment(
    val fileName: String,
    val contentType: String,
    val content: ByteArray,
) : ToJsonObject {
    companion object : FromJsonObject<EmailAttachment>
}
