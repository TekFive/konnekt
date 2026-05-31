package org.tekfive.konnekt.message.sms

import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals

class SmsMessageTest {

    @Test
    fun `sms message json round-trips address recipients`() {
        val message = SmsMessage(
            to = listOf(
                MessageAddress("+15555550100", "Primary"),
                MessageAddress("+15555550101", "Backup"),
            ),
            from = MessageAddress("+15555550999", "System"),
            body = "SMS body",
        )

        val restored = SmsMessage.fromJson(message.toJsonObject())

        assertEquals(message.to, restored.to)
        assertEquals(message.from, restored.from)
        assertEquals(message.body, restored.body)
    }

    @Test
    fun `queued sms message preserves queue metadata and payload`() {
        val message = QueuedSmsMessage(
            label = "sms-label",
            endpointId = "sms-endpoint",
            description = "sms-description",
            to = listOf(
                MessageAddress("+15555550100", "Primary"),
                MessageAddress("+15555550101", "Backup"),
            ),
            from = MessageAddress("+15555550999", "System"),
            body = "SMS body",
            trackReceipt = true,
            deliverAfter = 1234L,
            maxAttempts = 3,
        )

        val queued = message.toQueuedMessage()
        val restored = SmsMessage.fromJson(queued.message)

        assertEquals(MessageType.SMS, queued.type)
        assertEquals(listOf("+15555550100", "+15555550101"), queued.recipients)
        assertEquals("sms-endpoint", queued.providerTypeConfigurationId)
        assertEquals("sms-description", queued.description)
        assertEquals(true, queued.trackReceipt)
        assertEquals(1234L, queued.deliverAfter)
        assertEquals(3, queued.maxAttempts)
        assertEquals(message.to, restored.to)
        assertEquals(message.from, restored.from)
        assertEquals(message.body, restored.body)
    }
}
