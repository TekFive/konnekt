package org.tekfive.konnekt.llm.integration

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.tekfive.jfk.schema.booleanSchema
import org.tekfive.jfk.schema.integerSchema
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.LlmException
import org.tekfive.konnekt.llm.LlmMessage
import org.tekfive.konnekt.llm.LlmRequest
import org.tekfive.konnekt.llm.LlmResponse
import org.tekfive.konnekt.llm.LlmService
import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.stream.StreamListener
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Manual VLLM integration tests.
 *
 * Enable with `-Dkonnekt.integration.vllm=true` and provide:
 * `VLLM_BASE_URL` plus optional `VLLM_API_KEY` and `VLLM_MODEL`.
 */
class VllmIntegrationTest {

    companion object {
        private lateinit var endpoint: LlmEndpoint

        @BeforeAll
        @JvmStatic
        fun setup() {
            val pinnedCertFile = File(".env/vllm.cert")
            val pinnedCert = if (pinnedCertFile.isFile) {
                pinnedCertFile.readText()
            } else {
                null
            }

            endpoint = LlmEndpoint(
                providerType = LlmServiceProviderType.VLLM,
                model = ProviderIntegrationTestSupport.optionalEnv("VLLM_MODEL"),
                apiKey = ProviderIntegrationTestSupport.optionalEnv("VLLM_API_KEY"),
                baseUrl = ProviderIntegrationTestSupport.requiredEnv("VLLM_BASE_URL"),
                pinnedCertificate = pinnedCert,
            )
        }
    }

    @Test
    fun `chat returns assistant text`() {
        val response = LlmService.chat(
            LlmRequest(
                messages = listOf(LlmMessage.userMessage("Reply with a short greeting.")),
                endpoint = endpoint,
            )
        )

        assertTrue(response.content.isNotBlank())
        val responseEndpoint = assertNotNull(response.endpoint)
        assertEquals(LlmServiceProviderType.VLLM, responseEndpoint.providerType)
    }

    @Test
    fun `structured chat returns expected JSON fields`() {
        val schema = objectSchema {
            title = "PatientTriageSummary"
            properties {
                "summary" to stringSchema()
                "severity" to integerSchema()
                "requiresFollowUp" to booleanSchema()
            }
            required("summary", "severity", "requiresFollowUp")
        }

        val response = LlmService.structuredChat(
            systemPrompt = "Return structured JSON only.",
            userContent = "Patient has a mild cough for two days and no fever.",
            responseSchema = schema,
            endpoint = endpoint,
            temperature = 0.0,
        )

        assertNotNull(response["summary"].string)
        assertNotNull(response["severity"].int)
        assertNotNull(response["requiresFollowUp"].boolean)
    }

    @Test
    fun `streaming returns tokens and final response`() {
        val streamedTokens = mutableListOf<String>()
        var completed: LlmResponse? = null
        var streamError: LlmException? = null

        LlmService.chatStream(
            LlmRequest(
                messages = listOf(LlmMessage.userMessage("Reply with exactly: streaming-ok")),
                endpoint = endpoint,
                temperature = 0.0,
            ),
            object : StreamListener {
                override fun onToken(text: String) {
                    streamedTokens.add(text)
                }

                override fun onComplete(response: LlmResponse) {
                    completed = response
                }

                override fun onError(exception: LlmException) {
                    streamError = exception
                }
            },
        )

        assertNull(streamError)
        assertTrue(streamedTokens.isNotEmpty())
        assertNotNull(completed)
        assertTrue(completed!!.content.isNotBlank())
    }
}
