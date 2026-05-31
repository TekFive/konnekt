package org.tekfive.konnekt.llm

import org.tekfive.konnekt.llm.batch.BatchProvider
import org.tekfive.konnekt.llm.batch.MLBatchItem
import org.tekfive.konnekt.llm.batch.MLBatchResult
import org.tekfive.konnekt.llm.batch.MLBatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MLBatchTest {

    private val openaiEndpoint = LlmEndpoint(LlmServiceProviderType.OPENAI, LlmModel.GPT_4O, "sk-test")
    private val anthropicEndpoint = LlmEndpoint(LlmServiceProviderType.ANTHROPIC, LlmModel.CLAUDE_SONNET, "sk-test")

    private fun createBatchProvider(
        providerType: LlmServiceProviderType,
        batchId: String = "batch-123",
        statusSequence: List<MLBatchStatus> = listOf(MLBatchStatus.COMPLETED),
        results: List<MLBatchResult> = emptyList(),
    ): BatchProvider {
        var statusIndex = 0
        return object : ChatProvider, BatchProvider {
            override val type
                get() = providerType

            override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse {
                return LlmResponse.fromText(
                    text = "response-${request.messages.first().text}",
                    model = endpoint.model ?: "unknown",
                    inputTokens = 10,
                    outputTokens = 5,
                )
            }

            override fun submitBatch(items: List<MLBatchItem>, endpoint: LlmEndpoint): String {
                return batchId
            }

            override fun getBatchStatus(batchId: String, endpoint: LlmEndpoint): MLBatchStatus {
                val status = statusSequence[minOf(statusIndex, statusSequence.size - 1)]
                statusIndex++
                return status
            }

            override fun getBatchResults(batchId: String, endpoint: LlmEndpoint): List<MLBatchResult> {
                return results
            }
        }
    }

    @Test
    fun `batch provider submits and returns batch ID`() {
        val provider = createBatchProvider(LlmServiceProviderType.OPENAI, batchId = "batch-abc")
        val items = listOf(
            MLBatchItem("req-1", LlmRequest(listOf(LlmMessage.userMessage("Hello")), openaiEndpoint)),
        )

        val batchId = provider.submitBatch(items, openaiEndpoint)
        assertEquals("batch-abc", batchId)
    }

    @Test
    fun `batch provider returns status`() {
        val provider = createBatchProvider(
            LlmServiceProviderType.OPENAI,
            statusSequence = listOf(MLBatchStatus.IN_PROGRESS, MLBatchStatus.COMPLETED),
        )

        assertEquals(MLBatchStatus.IN_PROGRESS, provider.getBatchStatus("batch-123", openaiEndpoint))
        assertEquals(MLBatchStatus.COMPLETED, provider.getBatchStatus("batch-123", openaiEndpoint))
    }

    @Test
    fun `batch provider returns results`() {
        val expectedResults = listOf(
            MLBatchResult("req-1", LlmResponse.fromText("Hello!", "gpt-4o", 10, 3), null),
            MLBatchResult("req-2", LlmResponse.fromText("Hi!", "gpt-4o", 5, 2), null),
        )
        val provider = createBatchProvider(LlmServiceProviderType.OPENAI, results = expectedResults)

        val results = provider.getBatchResults("batch-123", openaiEndpoint)
        assertEquals(2, results.size)
        assertTrue(results[0].isSuccess)
        assertEquals("Hello!", results[0].response!!.content)
        assertTrue(results[1].isSuccess)
        assertEquals("Hi!", results[1].response!!.content)
    }

    @Test
    fun `batch provider handles failed status`() {
        val provider = createBatchProvider(
            LlmServiceProviderType.ANTHROPIC,
            statusSequence = listOf(MLBatchStatus.FAILED),
        )

        assertEquals(MLBatchStatus.FAILED, provider.getBatchStatus("batch-fail", anthropicEndpoint))
    }

    @Test
    fun `MLBatchResult isSuccess`() {
        val success = MLBatchResult("1", LlmResponse.fromText("ok", "test"), null)
        assertTrue(success.isSuccess)
        val failure = MLBatchResult("2", null, "error")
        assertFalse(failure.isSuccess)
    }
}
