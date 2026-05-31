package org.tekfive.konnekt.llm

import org.tekfive.konnekt.llm.stream.StreamListener
import org.tekfive.konnekt.llm.content.Tool
import org.tekfive.konnekt.llm.content.ToolChoice
import org.tekfive.konnekt.llm.content.ToolHandler

class Conversation(
    val endpoint: LlmEndpoint,
    val fallbackEndpoints: List<LlmEndpoint> = emptyList(),
    val systemPrompt: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool>? = null,
    val toolChoice: ToolChoice? = null,
    val toolHandler: ToolHandler? = null,
    val maxToolRounds: Int = 10,
) {
    private val _messages = mutableListOf<LlmMessage>()

    val messages: List<LlmMessage> get() = _messages.toList()

    fun say(text: String): LlmResponse {
        return say(listOf(LlmContentPart.Text(text)))
    }

    fun say(content: List<LlmContentPart>): LlmResponse {
        _messages.add(LlmMessage(PromptRole.USER, content))

        var rounds = 0
        while (true) {
            val response = LlmService.chat(buildRequest())
            _messages.add(LlmMessage(PromptRole.ASSISTANT, response.contentParts))

            if (!response.hasToolUse || toolHandler == null) {
                return response
            }

            rounds++
            if (rounds > maxToolRounds) {
                throw LlmException("Exceeded maximum tool call rounds ($maxToolRounds)")
            }

            for (toolUse in response.toolUses) {
                val result = toolHandler.execute(toolUse.name, toolUse.input)
                _messages.add(LlmMessage.toolResultMessage(toolUse.id, result.content, result.isError, toolUse.name))
            }
        }
    }

    fun stream(text: String, listener: StreamListener) {
        _messages.add(LlmMessage(PromptRole.USER, listOf(LlmContentPart.Text(text))))

        LlmService.chatStream(buildRequest(), object : StreamListener {
            override fun onToken(text: String) {
                listener.onToken(text)
            }

            override fun onComplete(response: LlmResponse) {
                _messages.add(LlmMessage(PromptRole.ASSISTANT, response.contentParts))
                listener.onComplete(response)
            }

            override fun onError(exception: LlmException) {
                listener.onError(exception)
            }
        })
    }

    fun addMessage(message: LlmMessage) {
        _messages.add(message)
    }

    fun clear() {
        _messages.clear()
    }

    private fun buildRequest(): LlmRequest {
        val allMessages = if (systemPrompt != null) {
            listOf(LlmMessage.systemMessage(systemPrompt)) + _messages
        } else {
            _messages.toList()
        }

        return LlmRequest(
            messages = allMessages,
            endpoint = endpoint,
            fallbackEndpoints = fallbackEndpoints,
            temperature = temperature,
            maxTokens = maxTokens,
            tools = tools,
            toolChoice = toolChoice,
        )
    }
}
