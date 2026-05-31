package org.tekfive.konnekt.message.email.providers.twilio

import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.email.EmailCapability
import org.tekfive.konnekt.message.email.EmailMessage
import org.tekfive.konnekt.message.email.EmailResponse
import org.tekfive.konnekt.message.email.EmailStatus
import org.tekfive.konnekt.message.email.providers.EmailProvider
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridContent
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridEmailAddress
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridEmailActivityResponse
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridEmailEvent
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridMailSendRequest
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridPersonalization

/**
 * Stateless Twilio SendGrid email sender that reads API settings from endpoint config.
 */
object TwilioEmailProvider : EmailProvider {

    val id: String = "twilio-sendgrid"
    val capabilities: Set<EmailCapability> = setOf(EmailCapability.STATUS_LOOKUP)

    override val supportsTracking: Boolean = true

    override fun send(message: EmailMessage, providerConfiguration: JsonObject): EmailResponse {
        val twilioSendGridConfiguration = TwilioSendGridConfiguration.fromJson(providerConfiguration)

        val client = TwilioSendGridClient(twilioSendGridConfiguration)
        val request = buildSendRequest(message)
        val response = client.sendMail(request)
        return EmailResponse(
            messageId = response.resolvedMessageId.orEmpty(),
            providerId = id,
            status = mapSendStatus(response.status),
        )
    }

    override fun status(messageId: String, providerConfiguration: JsonObject): EmailStatus? {
        val twilioSendGridConfiguration = TwilioSendGridConfiguration.fromJson(providerConfiguration)
        val client = TwilioSendGridClient(twilioSendGridConfiguration)
        val response = client.getEmailActivity(messageId) ?: return null
        return mapStatus(response)
    }

    fun mapStatus(events: List<TwilioSendGridEmailEvent>): EmailStatus {
        return events.asReversed().firstNotNullOfOrNull { event ->
            mapEvent(event)
        } ?: EmailStatus.UNKNOWN
    }

    private fun mapStatus(response: TwilioSendGridEmailActivityResponse): EmailStatus {
        val eventStatus = mapStatus(response.events)
        if (eventStatus != EmailStatus.UNKNOWN) {
            return eventStatus
        }

        return mapEventName(response.status) ?: EmailStatus.UNKNOWN
    }

    private fun mapSendStatus(status: String?): EmailStatus {
        return when (status?.lowercase()) {
            "queued", "accepted" -> EmailStatus.QUEUED
            "sent", "processed" -> EmailStatus.SENT
            else -> EmailStatus.QUEUED
        }
    }

    private fun mapEvent(event: TwilioSendGridEmailEvent): EmailStatus? {
        return mapEventName(event.event_name)
            ?: mapEventName(event.status)
    }

    private fun mapEventName(name: String?): EmailStatus? {
        return when (name?.lowercase()) {
            "open", "opened" -> EmailStatus.OPENED
            "delivered" -> EmailStatus.DELIVERED
            "processed", "sent" -> EmailStatus.SENT
            "bounce", "dropped", "deferred", "failed", "blocked" -> EmailStatus.FAILED
            else -> null
        }
    }

    private fun buildSendRequest(message: EmailMessage): TwilioSendGridMailSendRequest {
        val personalization = TwilioSendGridPersonalization(
            to = message.to.map(::toEmailAddress),
            cc = message.cc.map(::toEmailAddress),
            bcc = message.bcc.map(::toEmailAddress),
        )

        return TwilioSendGridMailSendRequest(
            personalizations = listOf(personalization),
            from = toEmailAddress(message.from),
            subject = message.subject,
            content = listOf(
                TwilioSendGridContent(
                    type = normalizeContentType(message.contentType),
                    value = message.body,
                ),
            ),
        )
    }

    private fun normalizeContentType(contentType: String): String {
        return when (contentType) {
            EmailMessage.TEXT_CONTENT_TYPE -> "text/plain"
            else -> contentType
        }
    }

    private fun toEmailAddress(address: MessageRecipient): TwilioSendGridEmailAddress {
        return TwilioSendGridEmailAddress(
            email = address.address,
            name = address.displayName,
        )
    }

    private fun toEmailAddress(address: MessageAddress): TwilioSendGridEmailAddress {
        return TwilioSendGridEmailAddress(
            email = address.address,
            name = address.displayName,
        )
    }
}
