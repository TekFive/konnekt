package org.tekfive.konnekt.message.email

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.konnekt.message.Message
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.MessageType


class EmailMessage(
    to: List<MessageRecipient>,
    val cc: List<MessageRecipient> = emptyList(),
    val bcc: List<MessageRecipient> = emptyList(),
    from: MessageAddress,
    val subject: String?,
    body: String,
    val contentType: String,
    val attachments: List<EmailAttachment> = emptyList(),
    ) : Message(
    to,
    from,
    body,
) {

    override val type: MessageType = MessageType.EMAIL

    companion object : FromJsonObject<EmailMessage> {
        const val TEXT_CONTENT_TYPE = "text/plain"

        const val HTML_CONTENT_TYPE = "text/html"
    }
}
