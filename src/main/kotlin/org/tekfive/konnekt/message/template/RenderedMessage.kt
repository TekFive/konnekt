package org.tekfive.konnekt.message.template

import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.email.EmailMessage

/** Output of the template rendering step, holding the rendered subject, bodies, and collected sensitivity tags. */
data class RenderedMessage(
    val subject: String,
    val htmlBody: String,
    val textBody: String,
    val sensitivityTags: Set<String> = emptySet(),
)

fun RenderedMessage.toEmailMessage(
    to: List<MessageRecipient>,
    from: MessageAddress,
    cc: List<MessageRecipient> = emptyList(),
    bcc: List<MessageRecipient> = emptyList(),
): EmailMessage {
    return EmailMessage(
        to = to,
        cc = cc,
        bcc = bcc,
        from = from,
        subject = subject,
        body = htmlBody,
        contentType = EmailMessage.HTML_CONTENT_TYPE,
    )
}

