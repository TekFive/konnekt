package org.tekfive.konnekt.message

import org.tekfive.jfk.json
import org.tekfive.konnekt.message.sms.QueuedSmsMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SendMessageJobTest {

    @Test
    fun `build message receipts stores provider tracking data for sms receipts`() {
        val queuedMessage = QueuedSmsMessage(
            label = "sms",
            endpointId = "twilio-endpoint",
            to = listOf(MessageAddress("+15555550100", "Primary")),
            from = MessageAddress("+15555550999", "System"),
            body = "Body",
            trackReceipt = true,
        ).toQueuedMessage()
        val receiptDetails = MessageReceiptDetails(
            endpointId = "twilio-endpoint",
            providerTrackingData = json {
                "endpointId" set "twilio-endpoint"
                "messageId" set "SM123"
            },
            recipientAddresses = listOf("+15555550100"),
        )

        val receipts = SendMessageJob.buildMessageReceipts(
            queuedMessageId = 42L,
            queuedMessage = queuedMessage,
            receiptDetails = receiptDetails,
        )

        assertEquals(1, receipts.size)
        assertEquals(1, queuedMessage.recipients.size)
        assertNotNull(queuedMessage.receiptDetails)
        assertEquals("twilio-endpoint", queuedMessage.receiptDetails?.string("endpointId"))
        assertEquals("SM123", queuedMessage.receiptDetails?.string("messageId"))
        assertEquals(MessageReceiptStatus.WAITING, receipts.single().status)
        assertEquals(receiptDetails.providerTrackingData.toJsonString(), receipts.single().details)
        assertEquals("+15555550100", receipts.single().recipientAddress)
        assertEquals(42L, receipts.single().queuedMessageId)
    }
}
