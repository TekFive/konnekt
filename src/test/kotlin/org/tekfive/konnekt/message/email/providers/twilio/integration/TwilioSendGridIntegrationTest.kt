package org.tekfive.konnekt.message.email.providers.twilio.integration

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.konnekt.llm.integration.ProviderIntegrationTestSupport
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.email.EmailMessage
import org.tekfive.konnekt.message.email.EmailStatus
import org.tekfive.konnekt.message.email.providers.twilio.TwilioEmailProvider
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Manual Twilio SendGrid integration test.
 *
 * Enable with `-Dkonnekt.integration.twilio.sendgrid=true` and provide:
 * `TWILIO_SENDGRID_API_KEY`, `TWILIO_SENDGRID_FROM_EMAIL`, and `TWILIO_SENDGRID_TO_EMAIL`,
 * plus optional `TWILIO_SENDGRID_FROM_NAME`, `TWILIO_SENDGRID_BASE_URL`,
 * `TWILIO_SENDGRID_POLL_INTERVAL_MS`, and `TWILIO_SENDGRID_POLL_TIMEOUT_MS`.
 */
class TwilioSendGridIntegrationTest {

    companion object {
        private lateinit var config: JsonObject
        private lateinit var message: EmailMessage
        private var pollIntervalMillis: Long = 3_000
        private var pollTimeoutMillis: Long = 60_000

        @BeforeAll
        @JvmStatic
        fun setup() {
            val apiKey = ProviderIntegrationTestSupport.requiredEnv("TWILIO_SENDGRID_API_KEY")
            val fromEmail = ProviderIntegrationTestSupport.requiredEnv("TWILIO_SENDGRID_FROM_EMAIL")
            val toEmail = ProviderIntegrationTestSupport.requiredEnv("TWILIO_SENDGRID_TO_EMAIL")
            val fromName = ProviderIntegrationTestSupport.optionalEnv("TWILIO_SENDGRID_FROM_NAME")
            val baseUrl = ProviderIntegrationTestSupport.optionalEnv("TWILIO_SENDGRID_BASE_URL")

            pollIntervalMillis = ProviderIntegrationTestSupport.longEnv("TWILIO_SENDGRID_POLL_INTERVAL_MS", 3_000)
            pollTimeoutMillis = ProviderIntegrationTestSupport.longEnv("TWILIO_SENDGRID_POLL_TIMEOUT_MS", 60_000)

            config = json {
                "apiKey" set apiKey
                if (baseUrl != null) {
                    "baseUrl" set baseUrl
                }
            }

            message = EmailMessage(
                to = listOf(MessageRecipient(toEmail, "Integration Recipient")),
                from = MessageAddress(fromEmail, fromName),
                subject = "Konnekt SendGrid integration test",
                body = "This is a real Twilio SendGrid integration test message.",
                contentType = EmailMessage.TEXT_CONTENT_TYPE,
            )
        }
    }

    @Test
    fun `sendgrid sender can poll a real message to a terminal success status`() {
        val sendResponse = TwilioEmailProvider.send(message, config)

        assertTrue(sendResponse.messageId.isNotBlank(), "Expected SendGrid to return a message id")
        assertTrue(
            sendResponse.status == EmailStatus.QUEUED || sendResponse.status == EmailStatus.SENT,
            "Expected initial SendGrid status to be QUEUED or SENT, but was ${sendResponse.status}",
        )

        val finalStatus = waitForTerminalStatus(sendResponse.messageId)

        assertTrue(
            finalStatus == EmailStatus.SENT ||
                finalStatus == EmailStatus.DELIVERED ||
                finalStatus == EmailStatus.OPENED,
            "Expected downstream success status to be SENT, DELIVERED, or OPENED, but was $finalStatus",
        )
    }

    private fun waitForTerminalStatus(messageId: String): EmailStatus {
        val deadline = System.currentTimeMillis() + pollTimeoutMillis
        var lastObservedStatus: EmailStatus? = null
        var observedNonNullStatus = false

        while (System.currentTimeMillis() < deadline) {
            when (val status = TwilioEmailProvider.status(messageId, config)) {
                null -> Thread.sleep(pollIntervalMillis)
                EmailStatus.QUEUED,
                EmailStatus.UNKNOWN,
                -> {
                    lastObservedStatus = status
                    observedNonNullStatus = true
                    Thread.sleep(pollIntervalMillis)
                }
                EmailStatus.FAILED -> fail("Twilio SendGrid message $messageId entered FAILED status")
                EmailStatus.DELIVERED,
                EmailStatus.OPENED,
                EmailStatus.SENT -> return status
            }
        }

        val timeoutMessage = if (!observedNonNullStatus) {
            "Timed out waiting for Twilio SendGrid message $messageId to reach a terminal success status; " +
                "status remained null, which may mean Email Activity is unavailable or not indexed yet"
        } else {
            "Timed out waiting for Twilio SendGrid message $messageId to reach a terminal success status; " +
                "last observed status was $lastObservedStatus"
        }

        fail(timeoutMessage)
    }
}
