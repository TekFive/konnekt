package org.tekfive.konnekt.message.email.providers.smtp

import jakarta.activation.DataHandler
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.tekfive.ack.Ack
import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.email.EmailAttachment
import org.tekfive.konnekt.message.email.EmailMessage
import org.tekfive.konnekt.message.email.EmailResponse
import org.tekfive.konnekt.message.email.EmailProviderType
import org.tekfive.konnekt.message.email.EmailStatus
import org.tekfive.konnekt.message.email.providers.EmailProvider
import java.util.Properties

/**
 * Stateless SMTP email sender that reads connection settings from a caller-supplied JsonObject
 * config rather than application-level Ack properties.
 */
object SmtpEmailProvider : EmailProvider{

    val connectionTimeoutDefaultMSecsAck = Ack.int("CONNECTION_TIMEOUT_DEFAULT_MSECS", 10_000, namespace = "SMTP", description = "Default SMTP connection timeout in milliseconds.")

    val timeoutDefaultMSecsAck = Ack.int("TIMEOUT_DEFAULT_MSECS", 10_000, namespace = "SMTP", description = "Default SMTP socket read timeout in milliseconds.")

    val writeTimeoutDefaultMSecsAck = Ack.int("WRITE_TIMEOUT_DEFAULT_MSECS", 10_000, namespace = "SMTP", description = "Default SMTP socket write timeout in milliseconds.")

    override val supportsTracking: Boolean = false

    override fun send(message: EmailMessage, providerConfiguration: JsonObject): EmailResponse {

        val smtpConfiguration = SmtpConfiguration.fromJson(providerConfiguration)

        val session = Session.getInstance(buildSessionProperties(smtpConfiguration), buildAuthenticator(smtpConfiguration))

        val mimeMessage = buildMimeMessage(message, session)
        Transport.send(mimeMessage)
        return EmailResponse(
            messageId = "",
            providerId = EmailProviderType.SMTP.providerId,
            status = EmailStatus.SENT,
        )
    }

    internal fun buildMimeMessage(message: EmailMessage, session: Session): MimeMessage {
        val mimeMessage = MimeMessage(session)
        mimeMessage.setFrom(toInternetAddress(message.from))
        mimeMessage.setRecipients(Message.RecipientType.TO, message.to.map(::toInternetAddress).toTypedArray())

        if (message.cc.isNotEmpty()) {
            mimeMessage.setRecipients(Message.RecipientType.CC, message.cc.map(::toInternetAddress).toTypedArray())
        }

        if (message.bcc.isNotEmpty()) {
            mimeMessage.setRecipients(Message.RecipientType.BCC, message.bcc.map(::toInternetAddress).toTypedArray())
        }

        if (!message.subject.isNullOrBlank()) {
            mimeMessage.setSubject(message.subject, Charsets.UTF_8.name())
        }

        if (message.attachments.isEmpty()) {
            mimeMessage.setContent(message.body, "${message.contentType}; charset=UTF-8")
        } else {
            val multipart = MimeMultipart("mixed")

            val bodyPart = MimeBodyPart()
            bodyPart.setContent(message.body, "${message.contentType}; charset=UTF-8")
            multipart.addBodyPart(bodyPart)

            for (attachment in message.attachments) {
                multipart.addBodyPart(buildAttachmentPart(attachment))
            }

            mimeMessage.setContent(multipart)
        }

        return mimeMessage
    }

    private fun buildAttachmentPart(attachment: EmailAttachment): MimeBodyPart {
        val part = MimeBodyPart()
        val dataSource = ByteArrayDataSource(attachment.content, attachment.contentType)
        part.dataHandler = DataHandler(dataSource)
        part.fileName = attachment.fileName
        part.disposition = MimeBodyPart.ATTACHMENT
        return part
    }

    override fun status(messageId: String, providerConfiguration: JsonObject): EmailStatus? {
        return null
    }

    internal fun buildSessionProperties(smtpConfiguration: SmtpConfiguration): Properties {
        val connectionTimeout = smtpConfiguration.connectionTimeoutMSecs
            ?: connectionTimeoutDefaultMSecsAck()

        val timeout = smtpConfiguration.timeoutMSecs
            ?: connectionTimeoutDefaultMSecsAck()

        val writeTimeout = smtpConfiguration.writeTimeoutMSecs
            ?: writeTimeoutDefaultMSecsAck()

        return Properties().apply {
            put("mail.smtp.host", smtpConfiguration.host)
            put("mail.smtp.port", (smtpConfiguration.port ?: 587).toString())
            put("mail.smtp.auth", smtpConfiguration.shouldAuthenticate.toString())
            put("mail.smtp.starttls.enable", (smtpConfiguration.startTls ?: true).toString())
            put("mail.smtp.ssl.enable", (smtpConfiguration.sslEnabled ?: false).toString())
            put("mail.smtp.connectiontimeout", connectionTimeout.toString())
            put("mail.smtp.timeout", timeout.toString())
            put("mail.smtp.writetimeout", writeTimeout.toString())
        }
    }

    internal fun buildAuthenticator(smtpConfiguration: SmtpConfiguration): Authenticator? {
        if (!smtpConfiguration.shouldAuthenticate) {
            return null
        }



        return object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(smtpConfiguration.username, smtpConfiguration.password)
            }
        }
    }

    private fun toInternetAddress(address: MessageAddress): InternetAddress {
        return if (address.displayName.isNullOrBlank()) {
            InternetAddress(address.address)
        } else {
            InternetAddress(address.address, address.displayName, Charsets.UTF_8.name())
        }
    }

    private fun toInternetAddress(address: MessageRecipient): InternetAddress {
        return if (address.displayName.isNullOrBlank()) {
            InternetAddress(address.address)
        } else {
            InternetAddress(address.address, address.displayName, Charsets.UTF_8.name())
        }
    }
}
