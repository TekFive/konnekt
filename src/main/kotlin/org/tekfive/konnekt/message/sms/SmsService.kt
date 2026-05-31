package org.tekfive.konnekt.message.sms

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessagingException
import org.tekfive.konnekt.message.MessageReceiptDetails
import org.tekfive.konnekt.message.QueuedMessage
import org.tekfive.konnekt.message.sms.providers.twilio.TwilioSmsSender
import java.io.IOException

object SmsService {

    private var resolver: SmsEndpointResolver? = null

    @Synchronized
    fun registerResolver(resolver: SmsEndpointResolver) {
        this.resolver = resolver
    }

    fun send(message: SmsMessage, endpoint: SmsEndpoint): SmsResponse {
        return try {
            senderFor(endpoint.provider).send(message, endpoint.config)
        } catch (e: MessagingException) {
            throw e
        } catch (e: IllegalStateException) {
            throw MessagingException(false, e.message ?: "Invalid SMS endpoint configuration", e)
        } catch (e: IllegalArgumentException) {
            throw MessagingException(false, e.message ?: "Invalid SMS request", e)
        } catch (e: IOException) {
            throw MessagingException(true, e.message ?: "SMS provider network failure", e)
        } catch (e: RuntimeException) {
            throw MessagingException(false, e.message ?: "SMS provider failure", e)
        }
    }

    internal fun send(queuedMessage: QueuedMessage): MessageReceiptDetails? {
        val endpoint = resolveEndpoint(queuedMessage.providerTypeConfigurationId)
        val message = SmsMessage.fromJson(queuedMessage.message)
        val response = send(message, endpoint)

        if (!queuedMessage.trackReceipt) {
            return null
        }

        if (!supportsStatusLookup(endpoint.provider) || response.messageId.isBlank()) {
            return null
        }

        val trackingData = json {
            "endpointId" set endpoint.id
            "messageId" set response.messageId
        }

        return MessageReceiptDetails(
            endpointId = endpoint.id,
            recipientAddresses = queuedMessage.recipients,
            providerTrackingData = trackingData,
        )
    }

    fun status(messageId: String, endpoint: SmsEndpoint): SmsStatus? {
        val sender = senderFor(endpoint.provider)
        if (!sender.capabilities.contains(SmsCapability.STATUS_LOOKUP)) {
            return null
        }
        return try {
            sender.status(messageId, endpoint.config)
        } catch (e: MessagingException) {
            throw e
        } catch (e: IllegalStateException) {
            throw MessagingException(false, e.message ?: "Invalid SMS endpoint configuration", e)
        } catch (e: IllegalArgumentException) {
            throw MessagingException(false, e.message ?: "Invalid SMS status request", e)
        } catch (e: IOException) {
            throw MessagingException(true, e.message ?: "SMS provider network failure", e)
        } catch (e: RuntimeException) {
            throw MessagingException(false, e.message ?: "SMS provider failure", e)
        }
    }

    internal fun reset() {
        resolver = null
    }

    private fun resolveEndpoint(endpointId: String): SmsEndpoint {
        val r = resolver ?: error("No SmsEndpointResolver registered. Call SmsService.registerResolver() at startup.")
        return r.resolve(endpointId) ?: error("SmsEndpoint not found for id: $endpointId")
    }

    private fun supportsStatusLookup(provider: SmsServiceProvider): Boolean {
        return senderFor(provider).capabilities.contains(SmsCapability.STATUS_LOOKUP)
    }

    private fun senderFor(provider: SmsServiceProvider): SmsSender {
        return when (provider) {
            SmsServiceProvider.TEST -> TestSmsSender
            SmsServiceProvider.TWILIO -> TwilioSmsSender
        }
    }
}

private object TestSmsSender : SmsSender {

    override val id: String = "test"

    override val active: Boolean = true

    override val capabilities: Set<SmsCapability> = emptySet()

    override fun send(message: SmsMessage, config: JsonObject): SmsResponse {
        return SmsResponse(
            messageId = "test-message-id",
            providerId = id,
            status = SmsStatus.SENT,
        )
    }

    override fun status(messageId: String, config: JsonObject): SmsStatus? {
        return null
    }
}
