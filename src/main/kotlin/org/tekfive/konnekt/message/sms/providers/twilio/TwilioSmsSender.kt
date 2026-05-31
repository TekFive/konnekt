package org.tekfive.konnekt.message.sms.providers.twilio

import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.message.sms.SmsCapability
import org.tekfive.konnekt.message.sms.SmsMessage
import org.tekfive.konnekt.message.sms.SmsResponse
import org.tekfive.konnekt.message.sms.SmsSender
import org.tekfive.konnekt.message.sms.SmsStatus
import org.tekfive.konnekt.message.sms.providers.twilio.model.TwilioSmsSendRequest

/**
 * Twilio SMS sender that maps Konnekt SMS messages onto Twilio's REST API.
 */
object TwilioSmsSender : SmsSender {

    internal var clientFactory: (TwilioSmsAuth) -> TwilioSmsClient = { auth ->
        TwilioSmsClient(auth)
    }

    override val id: String = "twilio"

    override val active: Boolean = true

    override val capabilities: Set<SmsCapability> = setOf(SmsCapability.STATUS_LOOKUP)

    override fun send(message: SmsMessage, config: JsonObject): SmsResponse {
        val auth = buildAuth(config)
        val client = clientFactory(auth)
        val recipients = message.to
        if (recipients.isEmpty()) {
            throw TwilioSmsException("Twilio SMS send requires at least one recipient")
        }
        if (recipients.size != 1) {
            throw TwilioSmsException("Twilio SMS sender supports exactly one recipient per send")
        }

        val senderConfig = buildSenderConfig(config)
        val recipient = recipients.single()

        val response = client.send(
            TwilioSmsSendRequest(
                to = recipient.address,
                from = senderConfig.fromNumber,
                messagingServiceSid = senderConfig.messagingServiceSid,
                body = message.body,
            ),
        )

        return SmsResponse(
            messageId = response.resolvedMessageId ?: "",
            providerId = id,
            status = mapStatus(response.status),
        )
    }

    override fun status(messageId: String, config: JsonObject): SmsStatus? {
        val auth = buildAuth(config)
        val client = clientFactory(auth)
        val response = client.getMessageStatus(messageId) ?: return null
        return mapStatus(response.status)
    }

    fun mapStatus(status: String?): SmsStatus {
        return when (status?.lowercase()) {
            "accepted", "queued", "scheduled" -> SmsStatus.QUEUED
            "sending", "sent" -> SmsStatus.SENT
            "delivered" -> SmsStatus.DELIVERED
            "failed", "undelivered", "canceled" -> SmsStatus.FAILED
            else -> SmsStatus.UNKNOWN
        }
    }

    private fun buildAuth(config: JsonObject): TwilioSmsAuth {
        val accountSid = config["accountSid"].string
            ?: error("Twilio SMS accountSid is required in endpoint config.")
        val authToken = config["authToken"].string
            ?: error("Twilio SMS authToken is required in endpoint config.")
        val baseUrl = config["baseUrl"].string ?: TwilioSmsAuth.DEFAULT_BASE_URL
        return TwilioSmsAuth(
            accountSid = accountSid,
            authToken = authToken,
            baseUrl = baseUrl,
        )
    }

    private fun buildSenderConfig(config: JsonObject): TwilioSmsSenderConfig {
        val messagingServiceSid = config["messagingServiceSid"].string?.trim().orEmpty()
            .ifBlank { null }
        val configuredFromNumber = config["fromNumber"].string?.trim().orEmpty()
            .ifBlank { null }

        if (messagingServiceSid != null && configuredFromNumber != null) {
            throw TwilioSmsException(
                "Twilio SMS endpoint config cannot specify both messagingServiceSid and fromNumber",
            )
        }

        if (messagingServiceSid == null && configuredFromNumber == null) {
            throw TwilioSmsException(
                "Twilio SMS send requires endpoint config to provide either messagingServiceSid or fromNumber",
            )
        }

        return TwilioSmsSenderConfig(
            fromNumber = configuredFromNumber,
            messagingServiceSid = messagingServiceSid,
        )
    }

    private data class TwilioSmsSenderConfig(
        val fromNumber: String?,
        val messagingServiceSid: String?,
    )
}
