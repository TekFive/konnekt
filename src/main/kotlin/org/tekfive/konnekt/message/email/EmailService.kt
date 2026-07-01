package org.tekfive.konnekt.message.email

import org.tekfive.ack.Ack
import org.tekfive.jfk.json
import org.tekfive.konnekt.HttpStatusException
import org.tekfive.konnekt.message.MessagingException
import org.tekfive.konnekt.message.QueuedMessage
import org.tekfive.konnekt.message.QueuedMessageTable
import org.tekfive.konnekt.message.MessageReceiptDetails
import org.tekfive.konnekt.message.QueuedMessageMetadata
import org.tekfive.konnekt.message.email.providers.twilio.TwilioSendGridException
import java.io.IOException

object EmailService {

    val maxAttachmentsSizeBytesAck = Ack.long("MAX_ATTACHMENTS_SIZE_BYTES", 25L * 1024 * 1024, min = 0L, namespace = "EMAIL", description = "Default maximum total size in bytes of all attachments on a single email, applied when the provider configuration does not specify its own limit.")

    @Volatile
    private var resolver: EmailProviderTypeConfigurationResolver? = null

    @Synchronized
    fun registerResolver(resolver: EmailProviderTypeConfigurationResolver) {
        this.resolver = resolver
    }

    @Synchronized
    internal fun reset() {
        resolver = null
    }

    fun send(message: EmailMessage, providerConfiguration: EmailProviderTypeConfiguration): EmailResponse {
        return dispatch(message, providerConfiguration)
    }

    fun queue(metadata: QueuedMessageMetadata, email: EmailMessage, providerTypeConfiguration: EmailProviderTypeConfiguration): Long {
        validateAttachmentsSize(email, providerTypeConfiguration)
        return QueuedMessageTable.create(QueuedMessage(metadata, email, providerTypeConfiguration)).id
    }

    fun status(messageId: String, endpoint: EmailProviderTypeConfiguration): EmailStatus? {
        val provider = endpoint.type
        if (!provider.capabilities.contains(EmailCapability.STATUS_LOOKUP)) {
            return null
        }

        return dispatchStatus(messageId, endpoint)
    }

    internal fun send(queuedMessage: QueuedMessage): MessageReceiptDetails? {
        val endpoint = resolveEndpoint(queuedMessage.providerTypeConfigurationId)
        val emailMessage = EmailMessage.fromJson(queuedMessage.message)
        val response = dispatch(emailMessage, endpoint)
        return if (
            queuedMessage.trackReceipt &&
            endpoint.type.capabilities.contains(EmailCapability.STATUS_LOOKUP) &&
            response.messageId.isNotBlank()
        ) {
            val trackingData = json {
                "endpointId" set endpoint.id
                "messageId" set response.messageId
            }
            MessageReceiptDetails(endpoint.id, trackingData, emailMessage.to, emailMessage.cc, emailMessage.bcc)
        } else {
            null
        }
    }

    /**
     * Dispatches to the provider and normalizes provider failures into [MessagingException] so the
     * retry machinery ([org.tekfive.konnekt.message.SendMessageJob]) can classify recoverability.
     * Wrapper messages are fixed, scrubbed strings — provider exception messages may not be reused
     * because they could carry addresses or response content.
     */
    private fun dispatch(message: EmailMessage, providerConfiguration: EmailProviderTypeConfiguration): EmailResponse {
        validateAttachmentsSize(message, providerConfiguration)
        val provider = providerConfiguration.type.provider
        return try {
            provider.send(message, providerConfiguration.configuration)
        } catch (e: MessagingException) {
            throw e
        } catch (e: TwilioSendGridException) {
            val statusCode = e.statusCode
            val recoverable = statusCode != null && MessagingException.isRecoverableStatus(statusCode)
            throw MessagingException(recoverable, "Email provider request failed with HTTP status ${statusCode ?: "unknown"}", e)
        } catch (e: HttpStatusException) {
            throw MessagingException(MessagingException.isRecoverableStatus(e.statusCode), "Email provider request failed with HTTP status ${e.statusCode}", e)
        } catch (e: IOException) {
            throw MessagingException(true, "Email provider network failure", e)
        } catch (e: IllegalStateException) {
            throw MessagingException(false, "Invalid email provider configuration", e)
        } catch (e: IllegalArgumentException) {
            throw MessagingException(false, "Invalid email request", e)
        } catch (e: RuntimeException) {
            throw MessagingException(false, "Email provider failure", e)
        }
    }

    private fun dispatchStatus(messageId: String, providerTypeConfiguration: EmailProviderTypeConfiguration): EmailStatus? {
        return providerTypeConfiguration.type.provider.status(messageId, providerTypeConfiguration.configuration)
    }

    /**
     * Enforces the total attachment size limit: the configuration's [EmailProviderTypeConfiguration.maxAttachmentsSizeBytes]
     * when set, otherwise the [maxAttachmentsSizeBytesAck] global default. Applied at queue time
     * (fail fast, before the message is persisted) and again at dispatch time as a backstop for
     * already-queued messages. The exception message carries sizes only — never attachment names
     * or content.
     */
    private fun validateAttachmentsSize(message: EmailMessage, providerConfiguration: EmailProviderTypeConfiguration) {
        if (message.attachments.isEmpty()) {
            return
        }

        val maxSizeBytes = providerConfiguration.maxAttachmentsSizeBytes ?: maxAttachmentsSizeBytesAck()
        var totalSizeBytes = 0L
        for (attachment in message.attachments) {
            totalSizeBytes += attachment.content.size
        }

        if (totalSizeBytes > maxSizeBytes) {
            throw MessagingException(false, "Email attachments total $totalSizeBytes bytes, exceeding the maximum of $maxSizeBytes bytes.")
        }
    }

    private fun resolveEndpoint(endpointId: String): EmailProviderTypeConfiguration {
        val resolver = resolver
            ?: throw IllegalStateException("No EmailProviderTypeConfigurationResolver registered. Call EmailService.registerResolver() at startup.")

        return resolver.resolve(endpointId)
            ?: throw IllegalArgumentException("EmailProviderTypeConfiguration not found for id: $endpointId")
    }
}
