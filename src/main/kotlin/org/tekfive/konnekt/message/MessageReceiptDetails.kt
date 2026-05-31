package org.tekfive.konnekt.message

import org.tekfive.jfk.JsonObject

class MessageReceiptDetails(
    val endpointId: String,
    val recipientAddresses: List<String>,
    val providerTrackingData: JsonObject
) {
    constructor(
        endpointId: String,
        providerTrackingData: JsonObject,
        recipients: List<MessageRecipient>,
        vararg moreRecipients: List<MessageRecipient>,
    ) : this(
        endpointId,
        (recipients + moreRecipients.toList().flatten()).map { it.address },
        providerTrackingData,
    )
}
