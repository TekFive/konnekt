package org.tekfive.konnekt.message.team

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.jfk.json
import org.tekfive.konnekt.TestDatabase
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.SendMessageJob
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.MessageType
import org.tekfive.konnekt.message.QueuedMessageTable
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class QueuedTeamMessageTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
    }

    @AfterTest
    fun cleanup() {
        TeamMessageService.reset()
    }

    @Test
    fun `queued team message preserves queued recipients and payload`() {
        val message = QueuedTeamMessage(
            label = "secure-label",
            endpointId = "tc-endpoint",
            description = "secure-description",
            to = listOf(
                MessageRecipient("user-1", "User One"),
                MessageRecipient("group-1", "Group One"),
            ),
            from = MessageAddress("system", "System"),
            subject = "Subject",
            body = "Body",
            attachments = listOf(
                TeamMessageAttachment(
                    fileName = "report.pdf",
                    contentType = "application/pdf",
                    content = byteArrayOf(0x1, 0x2),
                )
            ),
            priority = TeamMessagePriority.HIGH,
            trackReceipt = true,
            deliverAfter = 1234L,
            maxAttempts = 3,
        )

        val queued = message.toQueuedMessage()
        val restored = TeamMessage.fromJson(queued.message)

        assertEquals(MessageType.TEAM_MESSAGE, queued.type)
        assertEquals(listOf("user-1", "group-1"), queued.recipients)
        assertEquals("tc-endpoint", queued.providerTypeConfigurationId)
        assertEquals(true, queued.trackReceipt)
        assertEquals(1234L, queued.deliverAfter)
        assertEquals(3, queued.maxAttempts)
        assertEquals(message.to, restored.to)
        assertEquals(message.from, restored.from)
        assertEquals(message.subject, restored.subject)
        assertEquals(message.body, restored.body)
        assertEquals(message.attachments.size, restored.attachments.size)
        message.attachments.zip(restored.attachments).forEach { (expected, actual) ->
            assertEquals(expected.fileName, actual.fileName)
            assertEquals(expected.contentType, actual.contentType)
            assertContentEquals(expected.content, actual.content)
        }
        assertEquals(message.priority, restored.priority)
    }

    @Test
    fun `public team message queue persists queued message`() {
        transaction {
            SchemaUtils.create(QueuedMessageTable)
        }

        try {
            val queuedMessageId = TeamMessageService.queue(
                QueuedTeamMessage(
                    label = "secure-label",
                    endpointId = "tc-endpoint",
                    description = "secure-description",
                    to = listOf(MessageRecipient("user-1", "User One")),
                    from = MessageAddress("system", "System"),
                    subject = "Subject",
                    body = "Body",
                    trackReceipt = true,
                    deliverAfter = 1234L,
                    maxAttempts = 3,
                )
            )

            transaction {
                val row = QueuedMessageTable.selectAll()
                    .where { QueuedMessageTable.id eq queuedMessageId }
                    .single()

                assertEquals(MessageType.TEAM_MESSAGE, row[QueuedMessageTable.type])
                assertEquals(true, row[QueuedMessageTable.trackReceipt])
                assertEquals(1234L, row[QueuedMessageTable.deliverAfter])
                assertEquals(3, row[QueuedMessageTable.maxAttempts])
            }
        } finally {
            transaction {
                SchemaUtils.drop(QueuedMessageTable)
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires network access to external TigerConnect API")
    fun `queued secure send returns receipt details when tracking is requested and supported`() {
        registerTestResolver(testEndpoint())

        val queuedMessage = QueuedTeamMessage(
            label = "secure-label",
            endpointId = "test",
            to = listOf(MessageRecipient("user-1", "User One")),
            from = MessageAddress("system", "System"),
            subject = "Subject",
            body = "Body",
            trackReceipt = true,
        ).toQueuedMessage()

        val receiptDetails = TeamMessageService.send(queuedMessage)

        assertNotNull(receiptDetails)
        assertEquals("test", receiptDetails.endpointId)
        assertEquals(listOf("user-1"), receiptDetails.recipientAddresses)
        assertEquals("test", receiptDetails.providerTrackingData.string("endpointId"))
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires network access to external TigerConnect API")
    fun `queued secure send tracking identity is preserved for queued message and receipts`() {
        registerTestResolver(testEndpoint())

        val queuedMessage = QueuedTeamMessage(
            label = "secure-label",
            endpointId = "test",
            to = listOf(MessageRecipient("user-1", "User One")),
            from = MessageAddress("system", "System"),
            subject = "Subject",
            body = "Body",
            trackReceipt = true,
        ).toQueuedMessage()

        val receiptDetails = TeamMessageService.send(queuedMessage)
        assertNotNull(receiptDetails)

        val messageReceipts = SendMessageJob.buildMessageReceipts(
            queuedMessageId = 42L,
            queuedMessage = queuedMessage,
            receiptDetails = receiptDetails,
        )

        assertEquals("test", queuedMessage.receiptDetails?.string("endpointId"))
        assertEquals(1, messageReceipts.size)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires network access to external TigerConnect API")
    fun `queued secure send returns null when tracking is not requested`() {
        registerTestResolver(testEndpoint())

        val queuedMessage = QueuedTeamMessage(
            label = "secure-label",
            endpointId = "test",
            to = listOf(MessageRecipient("user-1", "User One")),
            from = MessageAddress("system", "System"),
            subject = "Subject",
            body = "Body",
            trackReceipt = false,
        ).toQueuedMessage()

        val receiptDetails = TeamMessageService.send(queuedMessage)

        assertNull(receiptDetails)
    }

    private fun testEndpoint(id: String = "test"): TeamMessageEndpoint {
        return TeamMessageEndpoint(
            id = id,
            provider = TeamMessageServiceProvider.TIGER_CONNECT,
            config = json {
                "apiKey" set "test-key"
                "apiSecret" set "test-secret"
            },
        )
    }

    private fun registerTestResolver(vararg endpoints: TeamMessageEndpoint) {
        val endpointMap = endpoints.associateBy { it.id }
        TeamMessageService.registerResolver { endpointId -> endpointMap[endpointId] }
    }
}
