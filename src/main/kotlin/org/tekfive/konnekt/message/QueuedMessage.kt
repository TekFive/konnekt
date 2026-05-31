package org.tekfive.konnekt.message

import org.tekfive.jfk.JsonObject
import org.tekfive.keep.data.Data

class QueuedMessage(
    val recipients: List<String>,
    val label: String,
    val providerTypeConfigurationId: String = "",
    val description: String?,
    val type: MessageType,
    val trackReceipt: Boolean,
    val message: JsonObject,
    internal var _state: QueuedMessageState = QueuedMessageState.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    var lastStateChangeAt: Long = createdAt,
    val deliverAfter: Long? = null,
    val maxAttempts: Int = 1,
    var receiptDetails: JsonObject? = null,
    var attemptCount: Int = 0,
    var maxReceiptWaitMinutes: Int? = null,
) : Data() {

    var state: QueuedMessageState
        get() = _state
        set(value) {
            _state = value
            lastStateChangeAt = System.currentTimeMillis()
        }

    constructor(metadata: QueuedMessageMetadata, message: Message, providerTypeConfiguration: MessageProviderTypeConfiguration) : this(
        recipients = message.to.map { it.address },
        label = metadata.label,
        providerTypeConfigurationId = providerTypeConfiguration.id,
        description = metadata.description,
        type = message.type,
        trackReceipt = metadata.trackReceipt,
        message = message.toJsonObject(),
        deliverAfter = metadata.deliverAfter,
        maxAttempts = metadata.maxAttempts,
        attemptCount = 0,
        maxReceiptWaitMinutes = metadata.maxReceiptWaitMinutes,
        receiptDetails = metadata.receiptDetails,
    )
}