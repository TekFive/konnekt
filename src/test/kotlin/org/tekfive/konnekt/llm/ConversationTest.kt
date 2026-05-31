package org.tekfive.konnekt.llm

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.schema.objectSchema
import org.tekfive.jfk.schema.stringSchema
import org.tekfive.konnekt.llm.stream.StreamListener
import org.tekfive.konnekt.llm.stream.StreamingProvider
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolHandler
import org.tekfive.konnekt.llm.content.ToolHandlerResult
import org.tekfive.konnekt.llm.providers.LlmServiceProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConversationTest {

    private val openaiEndpoint = LlmEndpoint(LlmServiceProviderType.OPENAI, LlmModel.GPT_4O, "sk-test")

    @AfterTest
    fun cleanup() {
        LlmService.clearOverrides()
        LlmService.clearCooldowns()
    }

    private fun setupChatProvider(responses: Iterator<LlmResponse>): ChatProvider {
        return object : ChatProvider {
            override val type
                get() = LlmServiceProviderType.OPENAI
            override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse = responses.next()
        }
    }

    private fun setupStreamingProvider(
        chatResponses: Iterator<LlmResponse>,
        streamTokens: List<String> = emptyList(),
        streamResponse: LlmResponse? = null,
    ): LlmServiceProvider {
        return object : ChatProvider, StreamingProvider {
            override val type
                get()= LlmServiceProviderType.OPENAI
            override fun chat(request: LlmRequest, endpoint: LlmEndpoint): LlmResponse = chatResponses.next()
            override fun chatStream(request: LlmRequest, endpoint: LlmEndpoint, listener: StreamListener) {
                streamTokens.forEach { listener.onToken(it) }
                listener.onComplete(streamResponse!!)
            }
        }
    }

    @Test
    fun `say adds user message and returns response`() {
        val responses = listOf(
            LlmResponse.fromText("Hello!", LlmModel.GPT_4O),
        ).iterator()

        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, setupChatProvider(responses))

        val conversation = Conversation(endpoint = openaiEndpoint)
        val response = conversation.say("Hi")

        assertEquals("Hello!", response.content)
        assertEquals(2, conversation.messages.size)
        assertEquals(PromptRole.USER, conversation.messages[0].role)
        assertEquals(PromptRole.ASSISTANT, conversation.messages[1].role)
    }

    @Test
    fun `say with system prompt includes it in request`() {
        val responses = listOf(
            LlmResponse.fromText("I'm helpful!", LlmModel.GPT_4O),
        ).iterator()

        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, setupChatProvider(responses))

        val conversation = Conversation(
            endpoint = openaiEndpoint,
            systemPrompt = "You are helpful.",
        )
        val response = conversation.say("Hi")

        assertEquals("I'm helpful!", response.content)
        assertEquals(2, conversation.messages.size)
    }

    @Test
    fun `say accumulates conversation history`() {
        val responses = listOf(
            LlmResponse.fromText("Hi!", LlmModel.GPT_4O),
            LlmResponse.fromText("I'm good!", LlmModel.GPT_4O),
        ).iterator()

        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, setupChatProvider(responses))

        val conversation = Conversation(endpoint = openaiEndpoint)
        conversation.say("Hello")
        conversation.say("How are you?")

        assertEquals(4, conversation.messages.size)
    }

    @Test
    fun `say with tool handler executes tool call loop`() {
        val toolUseResponse = LlmResponse(
            contentParts = listOf(
                LlmContentPart.ToolUse("call-1", "get_weather", JsonObject(mapOf("city" to "NYC"))),
            ),
            model = LlmModel.GPT_4O,
            finishReason = FinishReason.TOOL_USE,
        )
        val finalResponse = LlmResponse.fromText("It's 72F in NYC.", LlmModel.GPT_4O)

        val responses = listOf(toolUseResponse, finalResponse).iterator()
        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, setupChatProvider(responses))

        val tools = listOf(Tool("get_weather", "Get weather", objectSchema { properties { "city" to stringSchema() } }))
        val conversation = Conversation(
            endpoint = openaiEndpoint,
            tools = tools,
            toolHandler = ToolHandler { name, input ->
                ToolHandlerResult("72F and sunny")
            },
        )

        val response = conversation.say("What's the weather in NYC?")
        assertEquals("It's 72F in NYC.", response.content)
        assertEquals(4, conversation.messages.size)
    }

    @Test
    fun `say without tool handler returns tool use response as-is`() {
        val toolUseResponse = LlmResponse(
            contentParts = listOf(
                LlmContentPart.ToolUse("call-1", "get_weather", JsonObject(mapOf("city" to "NYC"))),
            ),
            model = LlmModel.GPT_4O,
            finishReason = FinishReason.TOOL_USE,
        )

        val responses = listOf(toolUseResponse).iterator()
        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, setupChatProvider(responses))

        val tools = listOf(Tool("get_weather", "Get weather", objectSchema { properties { "city" to stringSchema() } }))
        val conversation = Conversation(
            endpoint = openaiEndpoint,
            tools = tools,
        )

        val response = conversation.say("What's the weather?")
        assertTrue(response.hasToolUse)
        assertEquals(2, conversation.messages.size)
    }

    @Test
    fun `say throws when maxToolRounds exceeded`() {
        val toolUseResponse = LlmResponse(
            contentParts = listOf(
                LlmContentPart.ToolUse("call-1", "get_weather", JsonObject(mapOf("city" to "NYC"))),
            ),
            model = LlmModel.GPT_4O,
            finishReason = FinishReason.TOOL_USE,
        )

        val responses = generateSequence { toolUseResponse }.iterator()
        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, setupChatProvider(responses))

        val tools = listOf(Tool("get_weather", "Get weather", objectSchema { properties { "city" to stringSchema() } }))
        val conversation = Conversation(
            endpoint = openaiEndpoint,
            tools = tools,
            toolHandler = ToolHandler { _, _ -> ToolHandlerResult("result") },
            maxToolRounds = 3,
        )

        assertFailsWith<LlmException> {
            conversation.say("What's the weather?")
        }
    }

    @Test
    fun `clear resets conversation history`() {
        val responses = listOf(
            LlmResponse.fromText("Hi!", LlmModel.GPT_4O),
        ).iterator()

        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, setupChatProvider(responses))

        val conversation = Conversation(endpoint = openaiEndpoint)
        conversation.say("Hello")
        assertEquals(2, conversation.messages.size)

        conversation.clear()
        assertEquals(0, conversation.messages.size)
    }

    @Test
    fun `addMessage manually injects a message`() {
        val conversation = Conversation(endpoint = openaiEndpoint)
        conversation.addMessage(LlmMessage.assistantMessage("Prefilled"))
        assertEquals(1, conversation.messages.size)
        assertEquals(PromptRole.ASSISTANT, conversation.messages[0].role)
    }

    @Test
    fun `stream calls listener with tokens`() {
        val streamResponse = LlmResponse.fromText("hello world", LlmModel.GPT_4O)
        val chatResponses = listOf(streamResponse).iterator()

        LlmService.overrideProvider(LlmServiceProviderType.OPENAI, setupStreamingProvider(
            chatResponses,
            streamTokens = listOf("hello ", "world"),
            streamResponse = streamResponse,
        ))

        val conversation = Conversation(endpoint = openaiEndpoint)
        val tokens = mutableListOf<String>()
        var completed: LlmResponse? = null

        conversation.stream("Hi", object : StreamListener {
            override fun onToken(text: String) { tokens.add(text) }
            override fun onComplete(response: LlmResponse) { completed = response }
            override fun onError(exception: LlmException) {}
        })

        assertEquals(listOf("hello ", "world"), tokens)
        assertEquals("hello world", completed!!.content)
        assertEquals(2, conversation.messages.size)
    }
}
