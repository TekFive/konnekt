package org.tekfive.konnekt.message.sms

import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
            to = listOf(MessageAddress("+15555550100", "Primary")),
            from = MessageAddress("+15555550999", "System"),
            body = "SMS body",
            trackReceipt = true,
            deliverAfter = 1234L,
            maxAttempts = 3,
        )

        val queued = message.toQueuedMessage()
        val restored = SmsMessage.fromJson(queued.message)

        assertEquals(MessageType.SMS, queued.type)
        assertEquals(listOf("+15555550100"), queued.recipients)
        assertEquals("sms-endpoint", queued.providerTypeConfigurationId)
        assertEquals("sms-description", queued.description)
        assertEquals(true, queued.trackReceipt)
        assertEquals(1234L, queued.deliverAfter)
        assertEquals(3, queued.maxAttempts)
        assertEquals(message.to, restored.to)
        assertEquals(message.from, restored.from)
        assertEquals(message.body, restored.body)
    }

    @Test
    fun `queued sms message rejects multiple recipients at queue time`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            QueuedSmsMessage(
                label = "sms-label",
                endpointId = "sms-endpoint",
                to = listOf(
                    MessageAddress("+15555550100", "Primary"),
                    MessageAddress("+15555550101", "Backup"),
                ),
                from = MessageAddress("+15555550999", "System"),
                body = "SMS body",
            )
        }

        // Validation messages are persisted/logged — they must never echo the phone numbers.
        assertFalse(exception.message.orEmpty().contains("+15555550100"))
        assertFalse(exception.message.orEmpty().contains("+15555550101"))
    }

    @Test
    fun `queued sms message rejects blank recipient address at queue time`() {
        assertFailsWith<IllegalArgumentException> {
            QueuedSmsMessage(
                label = "sms-label",
                endpointId = "sms-endpoint",
                to = listOf(MessageAddress("   ", "Primary")),
                from = MessageAddress("+15555550999", "System"),
                body = "SMS body",
            )
        }
    }

    @Test
    fun `queued sms message rejects untrimmed from address at queue time`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            QueuedSmsMessage(
                label = "sms-label",
                endpointId = "sms-endpoint",
                to = listOf(MessageAddress("+15555550100", "Primary")),
                from = MessageAddress(" +15555550999 ", "System"),
                body = "SMS body",
            )
        }

        assertFalse(exception.message.orEmpty().contains("+15555550999"))
    }

    @Test
    fun `queued sms message accepts non-e164 sender identifiers`() {
        // Short codes and alphanumeric sender IDs are legitimate — no strict E.164 enforcement.
        val message = QueuedSmsMessage(
            label = "sms-label",
            endpointId = "sms-endpoint",
            to = listOf(MessageAddress("894546", "Short code")),
            from = MessageAddress("ACMECLINIC", "System"),
            body = "SMS body",
        )

        assertEquals(listOf("894546"), message.toQueuedMessage().recipients)
    }
}
