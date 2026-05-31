package org.tekfive.konnekt.message.email.integration

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.konnekt.llm.integration.ProviderIntegrationTestSupport
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.email.EmailMessage
import org.tekfive.konnekt.message.email.EmailStatus
import org.tekfive.konnekt.message.email.providers.zeptomail.ZeptoMailEmailProvider
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Manual ZeptoMail integration test.
 *
 * Enable with `-Dkonnekt.integration.zeptomail=true` and provide:
 * `ZEPTOMAIL_SEND_MAIL_TOKEN`, `ZEPTOMAIL_FROM_EMAIL`, and `ZEPTOMAIL_TO_EMAIL`,
 * plus optional `ZEPTOMAIL_FROM_NAME`, `ZEPTOMAIL_BASE_URL`,
 * `ZEPTOMAIL_OAUTH_ACCESS_TOKEN`, `ZEPTOMAIL_POLL_INTERVAL_MS`,
 * and `ZEPTOMAIL_POLL_TIMEOUT_MS`.
 *
 * Status polling requires an OAuth token with `Zeptomail.email.READ`
 * or `Zeptomail.email.ALL`.
 */
@EnabledIfSystemProperty(named = "konnekt.integration.zeptomail", matches = "true")
class ZeptoMailIntegrationTest {

    companion object {
        private lateinit var sendConfig: JsonObject
        private var statusConfig: JsonObject? = null
        private lateinit var message: EmailMessage
        private var pollIntervalMillis: Long = 3_000
        private var pollTimeoutMillis: Long = 60_000

        @BeforeAll
        @JvmStatic
        fun setup() {
            val sendMailToken = ProviderIntegrationTestSupport.requiredEnv("ZEPTOMAIL_SEND_MAIL_TOKEN")
            val fromEmail = ProviderIntegrationTestSupport.requiredEnv("ZEPTOMAIL_FROM_EMAIL")
            val toEmail = ProviderIntegrationTestSupport.requiredEnv("ZEPTOMAIL_TO_EMAIL")
            val fromName = ProviderIntegrationTestSupport.optionalEnv("ZEPTOMAIL_FROM_NAME")
            val baseUrl = ProviderIntegrationTestSupport.optionalEnv("ZEPTOMAIL_BASE_URL")
            val oauthAccessToken = ProviderIntegrationTestSupport.optionalEnv("ZEPTOMAIL_OAUTH_ACCESS_TOKEN")

            pollIntervalMillis = ProviderIntegrationTestSupport.longEnv("ZEPTOMAIL_POLL_INTERVAL_MS", 3_000)
            pollTimeoutMillis = ProviderIntegrationTestSupport.longEnv("ZEPTOMAIL_POLL_TIMEOUT_MS", 60_000)

            sendConfig = buildConfig(
                sendMailToken = sendMailToken,
                baseUrl = baseUrl,
                oauthAccessToken = null,
            )
            statusConfig = oauthAccessToken?.let { token ->
                buildConfig(
                    sendMailToken = sendMailToken,
                    baseUrl = baseUrl,
                    oauthAccessToken = token,
                )
            }

            message = EmailMessage(
                to = listOf(MessageRecipient(toEmail, "Integration Recipient")),
                from = MessageAddress(fromEmail, fromName),
                subject = "Konnekt ZeptoMail integration test ${System.currentTimeMillis()}",
                body = "This is a real ZeptoMail integration test message.",
                contentType = EmailMessage.TEXT_CONTENT_TYPE,
            )
        }

        private fun buildConfig(
            sendMailToken: String,
            baseUrl: String?,
            oauthAccessToken: String?,
        ): JsonObject {
            return json {
                "sendMailToken" set sendMailToken
                if (baseUrl != null) {
                    "baseUrl" set baseUrl
                }
                if (oauthAccessToken != null) {
                    "oauthAccessToken" set oauthAccessToken
                }
            }
        }
    }

    @Test
    fun `zeptomail sender can send a real message`() {
        val sendResponse = ZeptoMailEmailProvider.send(message, sendConfig)

        assertTrue(sendResponse.messageId.isNotBlank(), "Expected ZeptoMail to return a request id")
        assertTrue(
            sendResponse.status == EmailStatus.QUEUED || sendResponse.status == EmailStatus.SENT,
            "Expected initial ZeptoMail status to be QUEUED or SENT, but was ${sendResponse.status}",
        )
    }

    @Test
    fun `zeptomail sender can poll a real message to a terminal success status`() {
        assumeTrue(
            statusConfig != null,
            "Skipping ZeptoMail status polling test because ZEPTOMAIL_OAUTH_ACCESS_TOKEN is not set",
        )
        val config = statusConfig ?: error("ZeptoMail status config should be initialized when OAuth is present")

        val sendResponse = ZeptoMailEmailProvider.send(message, config)

        assertTrue(sendResponse.messageId.isNotBlank(), "Expected ZeptoMail to return a request id")

        val finalStatus = waitForTerminalStatus(sendResponse.messageId, config)

        assertTrue(
            finalStatus == EmailStatus.SENT ||
                finalStatus == EmailStatus.DELIVERED ||
                finalStatus == EmailStatus.OPENED,
            "Expected downstream success status to be SENT, DELIVERED, or OPENED, but was $finalStatus",
        )
    }

    private fun waitForTerminalStatus(messageId: String, config: JsonObject): EmailStatus {
        val deadline = System.currentTimeMillis() + pollTimeoutMillis
        var lastObservedStatus: EmailStatus? = null
        var observedNonNullStatus = false

        while (System.currentTimeMillis() < deadline) {
            when (val status = ZeptoMailEmailProvider.status(messageId, config)) {
                null -> Thread.sleep(pollIntervalMillis)
                EmailStatus.QUEUED,
                EmailStatus.UNKNOWN,
                -> {
                    lastObservedStatus = status
                    observedNonNullStatus = true
                    Thread.sleep(pollIntervalMillis)
                }
                EmailStatus.FAILED -> fail("ZeptoMail message $messageId entered FAILED status")
                EmailStatus.DELIVERED,
                EmailStatus.OPENED,
                EmailStatus.SENT -> return status
            }
        }

        val timeoutMessage = if (!observedNonNullStatus) {
            "Timed out waiting for ZeptoMail message $messageId to reach a terminal success status; " +
                "status remained null, which may mean the email log is not visible yet"
        } else {
            "Timed out waiting for ZeptoMail message $messageId to reach a terminal success status; " +
                "last observed status was $lastObservedStatus"
        }

        fail(timeoutMessage)
    }
}
