package org.tekfive.konnekt.message.email

import org.tekfive.jfk.json
import org.tekfive.konnekt.message.QueuedMessage
import org.tekfive.konnekt.message.QueuedMessageTable
import org.tekfive.konnekt.message.MessageReceiptDetails
import org.tekfive.konnekt.message.QueuedMessageMetadata

object EmailService {
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

    private fun dispatch(message: EmailMessage, providerConfiguration: EmailProviderTypeConfiguration): EmailResponse {
        val provider = providerConfiguration.type.provider
        return provider.send(message, providerConfiguration.configuration)
    }

    private fun dispatchStatus(messageId: String, providerTypeConfiguration: EmailProviderTypeConfiguration): EmailStatus? {
        return providerTypeConfiguration.type.provider.status(messageId, providerTypeConfiguration.configuration)
    }

    private fun resolveEndpoint(endpointId: String): EmailProviderTypeConfiguration {
        val resolver = resolver
            ?: throw IllegalStateException("No EmailProviderTypeConfigurationResolver registered. Call EmailService.registerResolver() at startup.")

        return resolver.resolve(endpointId)
            ?: throw IllegalArgumentException("EmailProviderTypeConfiguration not found for id: $endpointId")
    }
}
