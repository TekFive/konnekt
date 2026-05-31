package org.tekfive.konnekt.llm.integration

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.tekfive.jfk.schema.booleanSchema
import org.tekfive.jfk.schema.integerSchema
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.Conversation
import org.tekfive.konnekt.llm.LlmEndpoint
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
import org.tekfive.konnekt.llm.embedding.EmbeddingRequest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Manual OpenAI integration tests.
 *
 * Enable with `-Dkonnekt.integration.openai=true` and provide:
 * `OPENAI_API_KEY` plus optional `OPENAI_BASE_URL`, `OPENAI_CHAT_MODEL`,
 * `OPENAI_EMBEDDING_MODEL`, and `OPENAI_BATCH_MODEL`.
 */
class OpenAIIntegrationTest {

    companion object {
        private lateinit var chatEndpoint: LlmEndpoint
        private lateinit var embeddingEndpoint: LlmEndpoint
        private lateinit var batchEndpoint: LlmEndpoint

        @BeforeAll
        @JvmStatic
        fun setup() {
            val apiKey = ProviderIntegrationTestSupport.requiredEnv("OPENAI_API_KEY")
            val baseUrl = ProviderIntegrationTestSupport.optionalEnv("OPENAI_BASE_URL")
            chatEndpoint = openAiEndpoint(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = ProviderIntegrationTestSupport.optionalEnv("OPENAI_CHAT_MODEL") ?: "gpt-4o-mini",
            )
            embeddingEndpoint = openAiEndpoint(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = ProviderIntegrationTestSupport.optionalEnv("OPENAI_EMBEDDING_MODEL") ?: "text-embedding-3-small",
            )
            batchEndpoint = openAiEndpoint(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = ProviderIntegrationTestSupport.optionalEnv("OPENAI_BATCH_MODEL") ?: "gpt-4o-mini",
            )
        }

        private fun openAiEndpoint(apiKey: String, baseUrl: String?, model: String): LlmEndpoint {
            return LlmEndpoint(
                providerType = LlmServiceProviderType.OPENAI,
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
        assertEquals(LlmServiceProviderType.OPENAI, responseEndpoint.providerType)
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
    fun `embeddings returns vector data`() {
        val response = LlmService.embed(
            request = EmbeddingRequest(
                input = listOf("abnormal pathology result"),
                model = embeddingEndpoint.model ?: "text-embedding-3-small",
            ),
            endpoint = embeddingEndpoint,
        )

        assertEquals(1, response.embeddings.size)
        assertTrue(response.embeddings[0].isNotEmpty())
        assertEquals(embeddingEndpoint.model, response.model)
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
        repeat(40) {
            when (LlmService.getBatchStatus(batchId, batchEndpoint)) {
                MLBatchStatus.COMPLETED -> return LlmService.getBatchResults(batchId, batchEndpoint)
                MLBatchStatus.FAILED,
                MLBatchStatus.CANCELLED,
                MLBatchStatus.EXPIRED,
                    -> throw AssertionError("OpenAI batch $batchId finished in unexpected terminal state")
                MLBatchStatus.IN_PROGRESS -> Thread.sleep(3_000)
            }
        }

        throw AssertionError("Timed out waiting for OpenAI batch $batchId")
    }
}
