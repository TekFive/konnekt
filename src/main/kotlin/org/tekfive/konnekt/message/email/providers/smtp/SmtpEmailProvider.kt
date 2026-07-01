package org.tekfive.konnekt.message.email.providers.smtp

import jakarta.activation.DataHandler
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.MessagingException as JakartaMessagingException
import jakarta.mail.PasswordAuthentication
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.tekfive.ack.Ack
import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.MessagingException
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
        try {
            Transport.send(mimeMessage)
        } catch (e: SendFailedException) {
            val invalidCount = e.invalidAddresses?.size ?: 0
            val unsentCount = e.validUnsentAddresses?.size ?: 0
            // SendFailedException messages enumerate recipient addresses, so neither the original
            // message nor the cause chain may be propagated — counts only.
            throw MessagingException(false, "SMTP send failed: $invalidCount invalid / $unsentCount unsent recipient(s)")
        } catch (e: JakartaMessagingException) {
            // Connection-level failure messages carry host/port only, so chaining the cause is safe.
            throw MessagingException(true, "SMTP connection or protocol failure (${e.javaClass.simpleName})", e)
        }
        return EmailResponse(
            messageId = "",
            providerId = EmailProviderType.SMTP.providerId,
            status = EmailStatus.SENT,
        )
    }

    internal fun buildMimeMessage(message: EmailMessage, session: Session): MimeMessage {
        requireNoLineBreaks(message.contentType, "content type")

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
            mimeMessage.setSubject(stripLineBreaks(message.subject), Charsets.UTF_8.name())
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
        requireNoLineBreaks(attachment.contentType, "attachment content type")
        requireNoLineBreaks(attachment.fileName, "attachment file name")

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
            ?: timeoutDefaultMSecsAck()

        val writeTimeout = smtpConfiguration.writeTimeoutMSecs
            ?: writeTimeoutDefaultMSecsAck()

        val startTls = smtpConfiguration.startTls ?: true

        return Properties().apply {
            put("mail.smtp.host", smtpConfiguration.host)
            put("mail.smtp.port", (smtpConfiguration.port ?: 587).toString())
            put("mail.smtp.auth", smtpConfiguration.shouldAuthenticate.toString())
            put("mail.smtp.starttls.enable", startTls.toString())
            // When STARTTLS is enabled, refuse to fall back to plaintext if the server rejects it.
            put("mail.smtp.starttls.required", startTls.toString())
            put("mail.smtp.ssl.enable", (smtpConfiguration.sslEnabled ?: false).toString())
            // jakarta.mail 2.0.1 defaults server identity checking to false; always enable it.
            put("mail.smtp.ssl.checkserveridentity", "true")
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
        return toInternetAddress(address.address, address.displayName)
    }

    private fun toInternetAddress(address: MessageRecipient): InternetAddress {
        return toInternetAddress(address.address, address.displayName)
    }

    private fun toInternetAddress(address: String, displayName: String?): InternetAddress {
        requireNoLineBreaks(address, "address")

        // The 3-arg InternetAddress constructor does not validate the address, so validate()
        // must be called explicitly. AddressException messages include the raw address value,
        // so it is replaced with a scrubbed exception rather than rethrown or chained.
        try {
            val internetAddress = if (displayName.isNullOrBlank()) {
                InternetAddress(address)
            } else {
                InternetAddress(address, stripLineBreaks(displayName), Charsets.UTF_8.name())
            }
            internetAddress.validate()
            return internetAddress
        } catch (e: AddressException) {
            throw IllegalArgumentException("Invalid email address")
        }
    }

    private fun requireNoLineBreaks(value: String, description: String) {
        if (value.contains('\r') || value.contains('\n')) {
            throw IllegalArgumentException("Email $description contains illegal line break characters")
        }
    }

    private fun stripLineBreaks(value: String): String {
        return value.replace("\r", "").replace("\n", "")
    }
}
