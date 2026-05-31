package org.tekfive.konnekt.message.email.providers.zeptomail

import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.email.EmailMessage
import org.tekfive.konnekt.message.email.EmailProviderType
import org.tekfive.konnekt.message.email.EmailResponse
import org.tekfive.konnekt.message.email.EmailStatus
import org.tekfive.konnekt.message.email.providers.EmailProvider
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailEmailAddress
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailEmailStatusResponse
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailRecipient
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailSendRequest

/**
 * ZeptoMail sender using the send-mail token for outbound email and optional OAuth for status lookup.
 */
object ZeptoMailEmailProvider : EmailProvider {

    internal var clientFactory: (ZeptoMailConfiguration) -> ZeptoMailClient = { auth ->
        ZeptoMailClient(auth)
    }

    override val supportsTracking: Boolean = true

    override fun send(message: EmailMessage, configuration: JsonObject): EmailResponse {
        val zeptoMailConfiguration = ZeptoMailConfiguration.fromJson(configuration)
        val client = clientFactory(zeptoMailConfiguration)
        val response = client.sendMail(buildSendRequest(message, configuration))

        return EmailResponse(
            messageId = response.resolvedMessageId ?: "",
            providerId = EmailProviderType.ZEPTO_MAIL.providerId,
            status = mapSendStatus(response.status),
        )
    }

    override fun status(messageId: String, configuration: JsonObject): EmailStatus? {
        val zeptoMailConfiguration = ZeptoMailConfiguration.fromJson(configuration)
        if (zeptoMailConfiguration.oauthAuthorizationHeader == null) {
            return null
        }

        val client = clientFactory(zeptoMailConfiguration)
        val response = client.getEmailStatus(messageId) ?: return null
        return mapStatus(response)
    }

    fun mapStatus(response: ZeptoMailEmailStatusResponse): EmailStatus {
        if (response.openCount > 0) {
            return EmailStatus.OPENED
        }

        val hasFailure = response.hasHardBounceRecipients ||
            response.hasSoftBounceRecipients ||
            response.hasMailFailureRecipients ||
            response.hasProcessFailedRecipients
        val hasDeliveredRecipients = response.hasDeliveredRecipients

        if (hasFailure && hasDeliveredRecipients) {
            return EmailStatus.UNKNOWN
        }
        if (hasFailure) {
            return EmailStatus.FAILED
        }
        if (hasDeliveredRecipients) {
            return EmailStatus.DELIVERED
        }

        return mapProviderStatus(response.status)
    }

    private fun mapSendStatus(status: String?): EmailStatus {
        return when (status?.trim()?.lowercase()) {
            "success", "queued" -> EmailStatus.QUEUED
            "processed" -> EmailStatus.SENT
            else -> EmailStatus.QUEUED
        }
    }

    private fun mapProviderStatus(status: String?): EmailStatus {
        return when (status?.trim()?.lowercase()) {
            "queued" -> EmailStatus.QUEUED
            "processed" -> EmailStatus.SENT
            "delivered" -> EmailStatus.DELIVERED
            "hard bounce", "soft bounce", "mail failure", "process failed", "failed" -> EmailStatus.FAILED
            else -> EmailStatus.UNKNOWN
        }
    }

    private fun buildAuth(config: JsonObject): ZeptoMailConfiguration {
        val sendMailToken = config["sendMailToken"].string
            ?: error("ZeptoMail sendMailToken is required in endpoint config.")
        val oauthAccessToken = config["oauthAccessToken"].string
        val baseUrl = config["baseUrl"].string ?: ZeptoMailConfiguration.DEFAULT_BASE_URL
        return ZeptoMailConfiguration(
            sendMailToken = sendMailToken,
            oauthAccessToken = oauthAccessToken,
            baseUrl = baseUrl,
        )
    }

    private fun buildSendRequest(message: EmailMessage, config: JsonObject): ZeptoMailSendRequest {
        val body = buildBody(message)
        val bounceAddress = config["bounceAddress"].string?.trim().orEmpty().ifBlank { null }
        val trackOpens = config["trackOpens"].boolean
        val trackClicks = config["trackClicks"].boolean

        return ZeptoMailSendRequest(
            from = toEmailAddress(message.from),
            to = message.to.map(::toRecipient),
            cc = message.cc.map(::toRecipient),
            bcc = message.bcc.map(::toRecipient),
            subject = message.subject,
            textBody = body.textBody,
            htmlBody = body.htmlBody,
            trackOpens = trackOpens,
            trackClicks = trackClicks,
            bounceAddress = bounceAddress,
        )
    }

    private fun buildBody(message: EmailMessage): ZeptoMailBody {
        return when (message.contentType) {
            EmailMessage.TEXT_CONTENT_TYPE -> ZeptoMailBody(textBody = message.body, htmlBody = null)
            EmailMessage.HTML_CONTENT_TYPE -> ZeptoMailBody(textBody = null, htmlBody = message.body)
            else -> throw IllegalArgumentException(
                "ZeptoMail supports only ${EmailMessage.TEXT_CONTENT_TYPE} and ${EmailMessage.HTML_CONTENT_TYPE} content types",
            )
        }
    }

    private fun toRecipient(recipient: MessageRecipient): ZeptoMailRecipient {
        return ZeptoMailRecipient(
            emailAddress = ZeptoMailEmailAddress(
                address = recipient.address,
                name = recipient.displayName,
            ),
        )
    }

    private fun toEmailAddress(address: MessageAddress): ZeptoMailEmailAddress {
        return ZeptoMailEmailAddress(
            address = address.address,
            name = address.displayName,
        )
    }

    private data class ZeptoMailBody(
        val textBody: String?,
        val htmlBody: String?,
    )
}
