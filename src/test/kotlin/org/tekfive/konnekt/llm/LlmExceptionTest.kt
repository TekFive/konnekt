package org.tekfive.konnekt.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class LlmExceptionTest {
    @Test
    fun `provider response body is removed from API errors`() {
        val exception = LlmException("OpenAI API error 400: patient content from upstream")

        assertEquals("OpenAI API error 400", exception.message)
    }
}
