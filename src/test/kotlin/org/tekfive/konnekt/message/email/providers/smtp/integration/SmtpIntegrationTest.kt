package org.tekfive.konnekt.message.email.providers.smtp.integration

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobLogger
import org.tekfive.keep.job.JobResult
import org.tekfive.konnekt.TestDatabase
import org.tekfive.konnekt.llm.integration.ProviderIntegrationTestSupport
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.MessageReceiptTable
import org.tekfive.konnekt.message.QueuedMessageState
import org.tekfive.konnekt.message.QueuedMessageMetadata
import org.tekfive.konnekt.message.QueuedMessageTable
import org.tekfive.konnekt.message.SendMessageAttemptState
import org.tekfive.konnekt.message.SendMessageAttemptTable
import org.tekfive.konnekt.message.SendMessageJob
import org.tekfive.konnekt.message.email.EmailMessage
import org.tekfive.konnekt.message.email.EmailService
import org.tekfive.konnekt.message.email.EmailStatus
import org.tekfive.konnekt.message.email.EmailProviderTypeConfiguration
import org.tekfive.konnekt.message.email.EmailProviderType
import org.tekfive.konnekt.message.email.providers.smtp.SmtpEmailProvider
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Manual SMTP integration test.
 *
 * Enable with `-Dkonnekt.integration.smtp=true` and provide:
 * `SMTP_HOST`, `SMTP_FROM_EMAIL`, and `SMTP_TO_EMAIL`,
 * plus optional `SMTP_PORT`, `SMTP_FROM_NAME`, `SMTP_TO_NAME`,
 * `SMTP_STARTTLS`, `SMTP_SSL_ENABLED`, `SMTP_USERNAME`, and `SMTP_PASSWORD`.
 *
 * If authentication is needed, set both `SMTP_USERNAME` and `SMTP_PASSWORD`.
 */
class SmtpIntegrationTest {

    companion object {
        private const val endpointId = "smtp-integration-endpoint"

        private lateinit var config: JsonObject
        private lateinit var endpoint: EmailProviderTypeConfiguration
        private lateinit var message: EmailMessage

        @BeforeAll
        @JvmStatic
        fun setup() {
            AckRegistry.addSource(
                MapSource(
                    mapOf(
                        "EncryptionKeyset" to generateDummyEncryptionKeyset(),
                    )
                )
            )
            TestDatabase.connect()

            val host = ProviderIntegrationTestSupport.requiredEnv("SMTP_HOST")
            val fromEmail = ProviderIntegrationTestSupport.requiredEnv("SMTP_FROM_EMAIL")
            val toEmail = ProviderIntegrationTestSupport.requiredEnv("SMTP_TO_EMAIL")
            val fromName = ProviderIntegrationTestSupport.optionalEnv("SMTP_FROM_NAME")
            val toName = ProviderIntegrationTestSupport.optionalEnv("SMTP_TO_NAME")
            val username = ProviderIntegrationTestSupport.optionalEnv("SMTP_USERNAME")
            val password = ProviderIntegrationTestSupport.optionalEnv("SMTP_PASSWORD")

            require((username == null) == (password == null)) {
                "SMTP_USERNAME and SMTP_PASSWORD must either both be set or both be unset."
            }

            config = json {
                "host" set host
                "port" set intEnv("SMTP_PORT", 587)
                "startTls" set booleanEnv("SMTP_STARTTLS", true)
                "sslEnabled" set booleanEnv("SMTP_SSL_ENABLED", false)

                if (username != null && password != null) {
                    "authenticate" set true
                    "username" set username
                    "password" set password
                } else {
                    "authenticate" set false
                }
            }

            endpoint = EmailProviderTypeConfiguration(
                id = endpointId,
                type = EmailProviderType.SMTP,
                configuration = config,
            )

            message = EmailMessage(
                to = listOf(MessageRecipient(toEmail, toName)),
                from = MessageAddress(fromEmail, fromName),
                subject = "Konnekt SMTP integration test",
                body = "This is a real SMTP integration test message.",
                contentType = EmailMessage.TEXT_CONTENT_TYPE,
            )
        }

        @AfterAll
        @JvmStatic
        fun cleanupClass() {
            AckRegistry.clear()
        }

        private fun intEnv(name: String, defaultValue: Int): Int {
            val rawValue = ProviderIntegrationTestSupport.optionalEnv(name) ?: return defaultValue
            return rawValue.toIntOrNull()
                ?: error("Environment variable $name must be a valid integer, but was '$rawValue'.")
        }

        private fun booleanEnv(name: String, defaultValue: Boolean): Boolean {
            val rawValue = ProviderIntegrationTestSupport.optionalEnv(name) ?: return defaultValue
            return when (rawValue.lowercase()) {
                "true" -> true
                "false" -> false
                else -> error("Environment variable $name must be 'true' or 'false', but was '$rawValue'.")
            }
        }

        private fun generateDummyEncryptionKeyset(): String {
            val insecureSecretKeyAccess = Class.forName("com.google.crypto.tink.InsecureSecretKeyAccess")
            val accessGet = insecureSecretKeyAccess.getMethod("get")
            val secretKeyAccess = accessGet.invoke(null)

            val aeadConfig = Class.forName("com.google.crypto.tink.aead.AeadConfig")
            aeadConfig.getMethod("register").invoke(null)

            val predefinedAeadParameters = Class.forName("com.google.crypto.tink.aead.PredefinedAeadParameters")
            val aes256Gcm = predefinedAeadParameters.getField("AES256_GCM").get(null)

            val keysetHandle = Class.forName("com.google.crypto.tink.KeysetHandle")
            val generateNew = keysetHandle.getMethod(
                "generateNew",
                Class.forName("com.google.crypto.tink.Parameters"),
            )
            val handle = generateNew.invoke(null, aes256Gcm)

            val tinkJsonProtoKeysetFormat = Class.forName("com.google.crypto.tink.TinkJsonProtoKeysetFormat")
            val serializeKeyset = tinkJsonProtoKeysetFormat.getMethod(
                "serializeKeyset",
                keysetHandle,
                Class.forName("com.google.crypto.tink.SecretKeyAccess"),
            )
            return serializeKeyset.invoke(null, handle, secretKeyAccess) as String
        }
    }

    @AfterTest
    fun tearDown() {
        EmailService.reset()
    }

    @Test
    fun `smtp sender can send a real message`() {
        val response = SmtpEmailProvider.send(message, config)

        assertEquals("", response.messageId)
        assertEquals("smtp", response.providerId)
        assertEquals(EmailStatus.SENT, response.status)
    }

    @Test
    fun `queued smtp email can be sent end to end through send message job`() {
        transaction {
            SchemaUtils.create(QueuedMessageTable, SendMessageAttemptTable, MessageReceiptTable)
        }

        try {
            EmailService.registerResolver { resolvedEndpointId ->
                if (resolvedEndpointId == endpoint.id) endpoint else null
            }

            val queuedMessageId = EmailService.queue(
                metadata = QueuedMessageMetadata(
                    label = "smtp-queued-integration",
                    description = null,
                    trackReceipt = true,
                ),
                email = message,
                providerTypeConfiguration = endpoint,
            )

            transaction {
                val queuedRow = QueuedMessageTable.selectAll()
                    .where { QueuedMessageTable.id eq queuedMessageId }
                    .single()

                assertEquals(QueuedMessageState.QUEUED, queuedRow[QueuedMessageTable.state])
                assertEquals(0, queuedRow[QueuedMessageTable.attemptCount])
                assertNull(queuedRow[QueuedMessageTable.receiptDetails])
            }

            transaction {
                val queuedMessage = QueuedMessageTable.findById(queuedMessageId)!!
                queuedMessage.state = QueuedMessageState.PENDING
                QueuedMessageTable.update(queuedMessage)
            }

            val result = SendMessageJob().execute(
                createJobContext(
                    details = json {
                        SendMessageJob.QUEUED_MESSAGE_ID_PROPERTY set queuedMessageId
                    }
                )
            )

            assertTrue(result is JobCompleted)

            transaction {
                val queuedRow = QueuedMessageTable.selectAll()
                    .where { QueuedMessageTable.id eq queuedMessageId }
                    .single()

                assertEquals(QueuedMessageState.SENT, queuedRow[QueuedMessageTable.state])
                assertEquals(1, queuedRow[QueuedMessageTable.attemptCount])
                assertNull(queuedRow[QueuedMessageTable.receiptDetails])
            }

            transaction {
                val attempts = SendMessageAttemptTable.selectAll()
                    .where { SendMessageAttemptTable.queuedMessageId eq queuedMessageId }
                    .toList()

                assertEquals(1, attempts.size)
                assertEquals(SendMessageAttemptState.SENT, attempts.single()[SendMessageAttemptTable.state])
            }

            transaction {
                val receipts = MessageReceiptTable.selectAll()
                    .where { MessageReceiptTable.queuedMessageId eq queuedMessageId }
                    .toList()

                assertTrue(receipts.isEmpty())
            }
        } finally {
            transaction {
                SchemaUtils.drop(MessageReceiptTable, SendMessageAttemptTable, QueuedMessageTable)
            }
        }
    }

    private fun createJobContext(details: JsonObject): JobContext {
        val job = object : Job {
            override fun execute(context: JobContext): JobResult = JobCompleted()
        }

        return object : JobContext {
            override val jobId: Long = 1L
            override val startedAt: Long = System.currentTimeMillis()
            override val type: String = "smtp-integration"
            override val createdAt: Long = startedAt
            override val attempt: Int = 1
            override val maxRetries: Int = 0
            override val estimatedRuntimeSeconds: Int? = null
            override var details: JsonObject? = details
            override val log: JobLogger = JobLogger(job, this, null)

            override fun checkIn(now: Long) {}

            override fun updateDetails(details: JsonObject) {
                this.details = details
            }
        }
    }

}
