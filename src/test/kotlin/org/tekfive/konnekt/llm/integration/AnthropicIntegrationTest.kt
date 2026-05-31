package org.tekfive.konnekt.llm.integration

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.tekfive.jfk.schema.booleanSchema
import org.tekfive.jfk.schema.integerSchema
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.Conversation
import org.tekfive.konnekt.llm.LlmEndpoint
import org.tekfive.konnekt.llm.LlmModel
import org.tekfive.konnekt.llm.LlmMessage
import org.tekfive.konnekt.llm.LlmRequest
import org.tekfive.konnekt.llm.LlmService
import org.tekfive.konnekt.llm.LlmServiceProviderType
import org.tekfive.konnekt.llm.batch.MLBatchItem
import org.tekfive.konnekt.llm.batch.MLBatchResult
import org.tekfive.konnekt.llm.batch.MLBatchStatus
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import org.tekfive.konnekt.llm.content.ToolHandler
import org.tekfive.konnekt.llm.content.ToolHandlerResult
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Manual Anthropic integration tests.
 *
 * Enable with `-Dkonnekt.integration.anthropic=true` and provide:
 * `ANTHROPIC_API_KEY` plus optional `ANTHROPIC_BASE_URL`,
 * `ANTHROPIC_CHAT_MODEL`, and `ANTHROPIC_BATCH_MODEL`.
 */
@EnabledIfSystemProperty(named = "konnekt.integration.anthropic", matches = "true")
class AnthropicIntegrationTest {

    private val batchPollAttempts = ProviderIntegrationTestSupport.longEnv("ANTHROPIC_BATCH_POLL_ATTEMPTS", 60)
    private val batchPollDelayMillis = ProviderIntegrationTestSupport.longEnv("ANTHROPIC_BATCH_POLL_DELAY_MILLIS", 5_000)

    companion object {
        private lateinit var chatEndpoint: LlmEndpoint
        private lateinit var batchEndpoint: LlmEndpoint

        @BeforeAll
        @JvmStatic
        fun setup() {
            val apiKey = ProviderIntegrationTestSupport.requiredEnv("ANTHROPIC_API_KEY")
            val baseUrl = ProviderIntegrationTestSupport.optionalEnv("ANTHROPIC_BASE_URL")
            chatEndpoint = anthropicEndpoint(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = ProviderIntegrationTestSupport.optionalEnv("ANTHROPIC_CHAT_MODEL") ?: LlmModel.CLAUDE_HAIKU,
            )
            batchEndpoint = anthropicEndpoint(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = ProviderIntegrationTestSupport.optionalEnv("ANTHROPIC_BATCH_MODEL") ?: LlmModel.CLAUDE_HAIKU,
            )
        }

        private fun anthropicEndpoint(apiKey: String, baseUrl: String?, model: String): LlmEndpoint {
            return LlmEndpoint(
                providerType = LlmServiceProviderType.ANTHROPIC,
                model = model,
                apiKey = apiKey,
                baseUrl = baseUrl,
            )
        }
    }

    @Test
    fun `chat returns assistant text`() {
        val response = LlmService.chat(
            LlmRequest(
                messages = listOf(LlmMessage.userMessage("Reply with a short greeting.")),
                endpoint = chatEndpoint,
            )
        )

        assertTrue(response.content.isNotBlank())
        val responseEndpoint = assertNotNull(response.endpoint)
        assertEquals(LlmServiceProviderType.ANTHROPIC, responseEndpoint.providerType)
    }

    @Test
    fun `structured chat returns expected JSON fields`() {
        val schema = objectSchema {
            title = "PatientTriageSummary"
            description = "Return triage details"
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
            endpoint = chatEndpoint,
            temperature = 0.0,
        )

        assertNotNull(response["summary"].string)
        assertNotNull(response["severity"].int)
        assertNotNull(response["requiresFollowUp"].boolean)
    }

    @Test
    fun `conversation completes tool round trip`() {
        var toolCalls = 0
        val tool = Tool(
            name = "get_clinic_status",
            description = "Returns whether the clinic is open and how many providers are on shift.",
            parameters = objectSchema {
                properties {
                    "clinicId" to stringSchema()
                }
                required("clinicId")
            },
        )
        val conversation = Conversation(
            endpoint = chatEndpoint,
            tools = listOf(tool),
            toolChoice = ToolChoice.Auto,
            toolHandler = ToolHandler { name, input ->
                toolCalls += 1
                assertEquals("get_clinic_status", name)
                assertNotNull(input["clinicId"].string)
                ToolHandlerResult("""{"open":true,"providersOnShift":4}""")
            },
        )

        val response = conversation.say("Use the clinic status tool for clinic 'north' and tell me if it is open.")

        assertEquals(1, toolCalls)
        assertTrue(response.content.contains("open", ignoreCase = true))
    }

    @Test
    fun `batch completes and returns results`() {
        val items = listOf(
            MLBatchItem(
                id = "chat-1",
                request = LlmRequest(
                    messages = listOf(LlmMessage.userMessage("Reply with the word alpha.")),
                    endpoint = batchEndpoint,
                ),
            ),
            MLBatchItem(
                id = "chat-2",
                request = LlmRequest(
                    messages = listOf(LlmMessage.userMessage("Reply with the word beta.")),
                    endpoint = batchEndpoint,
                ),
            ),
        )

        val batchId = LlmService.submitBatch(items, batchEndpoint)
        val results = waitForBatchResults(batchId)

        assertEquals(2, results.size)
        assertEquals(setOf("chat-1", "chat-2"), results.map { it.id }.toSet())
        assertTrue(results.all { it.isSuccess })
        assertTrue(results.all { it.response?.content?.isNotBlank() == true })
    }

    private fun waitForBatchResults(batchId: String): List<MLBatchResult> {
        repeat(batchPollAttempts.toInt()) {
            when (LlmService.getBatchStatus(batchId, batchEndpoint)) {
                MLBatchStatus.COMPLETED -> return LlmService.getBatchResults(batchId, batchEndpoint)
                MLBatchStatus.FAILED,
                MLBatchStatus.CANCELLED,
                MLBatchStatus.EXPIRED,
                    -> throw AssertionError("Anthropic batch $batchId finished in unexpected terminal state")
                MLBatchStatus.IN_PROGRESS -> Thread.sleep(batchPollDelayMillis)
            }
        }

        throw AssertionError(
            "Timed out waiting for Anthropic batch $batchId after ${batchPollAttempts * batchPollDelayMillis} ms"
        )
    }
}
