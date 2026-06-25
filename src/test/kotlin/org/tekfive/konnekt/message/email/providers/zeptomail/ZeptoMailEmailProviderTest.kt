package org.tekfive.konnekt.message.email.providers.zeptomail

import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.email.EmailAttachment
import org.tekfive.konnekt.message.email.EmailMessage
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailAttachment
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailEmailAddress
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailRecipient
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailSendRequest
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailSendResponse
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ZeptoMailEmailProviderTest {

    @AfterTest
    fun tearDown() {
        ZeptoMailEmailProvider.clientFactory = { auth -> ZeptoMailClient(auth) }
    }

    @Test
    fun `zeptomail request serializes attachments array`() {
        val request = ZeptoMailSendRequest(
            from = ZeptoMailEmailAddress("from@example.com"),
            to = listOf(ZeptoMailRecipient(ZeptoMailEmailAddress("to@example.com"))),
            subject = "Subject",
            textBody = "Body",
            attachments = listOf(
                ZeptoMailAttachment(content = "UERGREFUQQ==", mimeType = "application/pdf", name = "report.pdf"),
            ),
        )

        val serialized = request.toJsonString()

        assertEquals(true, serialized.contains("\"attachments\""))
        assertEquals(true, serialized.contains("\"name\":\"report.pdf\""))
        assertEquals(true, serialized.contains("\"mime_type\":\"application/pdf\""))
    }

    @Test
    fun `zeptomail request omits attachments when empty`() {
        val request = ZeptoMailSendRequest(
            from = ZeptoMailEmailAddress("from@example.com"),
            to = listOf(ZeptoMailRecipient(ZeptoMailEmailAddress("to@example.com"))),
            textBody = "Body",
        )

        assertEquals(false, request.toJsonString().contains("attachments"))
    }

    @Test
    fun `zeptomail provider maps message attachments into send request`() {
        var captured: ZeptoMailSendRequest? = null
        ZeptoMailEmailProvider.clientFactory = { auth ->
            object : ZeptoMailClient(auth) {
                override fun sendMail(requestBody: ZeptoMailSendRequest): ZeptoMailSendResponse {
                    captured = requestBody
                    return ZeptoMailSendResponse(status = "success")
                }
            }
        }

        ZeptoMailEmailProvider.send(
            message = EmailMessage(
                to = listOf(MessageRecipient("to@example.com", "To")),
                from = MessageAddress("from@example.com", "From"),
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
            ),
            configuration = json {
                "sendMailToken" set "token"
            },
        )

        val attachment = captured?.attachments?.single()
        assertEquals("report.pdf", attachment?.name)
        assertEquals("application/pdf", attachment?.mimeType)
        assertEquals(Base64.getEncoder().encodeToString("PDFDATA".toByteArray(Charsets.UTF_8)), attachment?.content)
    }
}
