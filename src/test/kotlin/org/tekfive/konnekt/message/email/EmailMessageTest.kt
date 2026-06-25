package org.tekfive.konnekt.message.email

import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.QueuedMessage
import org.tekfive.konnekt.message.QueuedMessageMetadata
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class EmailMessageTest {

    @Test
    fun `queued email message preserves queued recipients`() {
        val message = EmailMessage(
            to = listOf(MessageRecipient("to@example.com", "To")),
            cc = listOf(MessageRecipient("cc@example.com", "CC")),
            bcc = listOf(MessageRecipient("bcc@example.com", "BCC")),
            from = MessageAddress("sender@example.com", "Sender"),
            subject = "Subject",
            body = "Body",
            contentType = EmailMessage.TEXT_CONTENT_TYPE,
        )

        val queued = QueuedMessage(
            metadata = QueuedMessageMetadata(
                label = "label",
                description = null,
                trackReceipt = false,
            ),
            message = message,
            providerTypeConfiguration = EmailProviderTypeConfiguration(
                id = "test-endpoint",
                type = EmailProviderType.SMTP,
                configuration = org.tekfive.jfk.json {
                    "host" set "smtp.example.com"
                },
            ),
        )
        val restored = EmailMessage.fromJson(queued.message)

        assertEquals(listOf("to@example.com"), queued.recipients)
        assertEquals(message.to, restored.to)
        assertEquals(message.cc, restored.cc)
        assertEquals(message.bcc, restored.bcc)
    }

    @Test
    fun `queued email message preserves attachments`() {
        val message = EmailMessage(
            to = listOf(MessageRecipient("to@example.com", "To")),
            from = MessageAddress("sender@example.com", "Sender"),
            subject = "Subject",
            body = "Body",
            contentType = EmailMessage.TEXT_CONTENT_TYPE,
            attachments = listOf(
                EmailAttachment(
                    fileName = "report.pdf",
                    contentType = "application/pdf",
                    content = "PDFDATA".toByteArray(Charsets.UTF_8),
                ),
            ),
        )

        val restored = EmailMessage.fromJson(message.toJsonObject())

        assertEquals(1, restored.attachments.size)
        val attachment = restored.attachments.single()
        assertEquals("report.pdf", attachment.fileName)
        assertEquals("application/pdf", attachment.contentType)
        assertContentEquals("PDFDATA".toByteArray(Charsets.UTF_8), attachment.content)
    }

    @Test
    fun `email message defaults to no attachments`() {
        val message = EmailMessage(
            to = listOf(MessageRecipient("to@example.com", "To")),
            from = MessageAddress("sender@example.com", "Sender"),
            subject = "Subject",
            body = "Body",
            contentType = EmailMessage.TEXT_CONTENT_TYPE,
        )

        assertEquals(emptyList(), EmailMessage.fromJson(message.toJsonObject()).attachments)
    }
}
