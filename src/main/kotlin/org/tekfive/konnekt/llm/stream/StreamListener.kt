package org.tekfive.konnekt.llm.stream

import org.tekfive.konnekt.llm.LlmException
import org.tekfive.konnekt.llm.LlmResponse

interface StreamListener {
    fun onToken(text: String)
    fun onReasoningToken(text: String) {}
    fun onComplete(response: LlmResponse)
    fun onError(exception: LlmException)
}
