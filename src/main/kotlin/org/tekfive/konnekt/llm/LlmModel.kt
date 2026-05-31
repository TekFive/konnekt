package org.tekfive.konnekt.llm

/**
 * Known ML model identifiers. The provider is determined automatically
 * from the model ID prefix via [LlmServiceProviderType.forModel].
 */
object LlmModel {
    const val CLAUDE_SONNET = "claude-sonnet-4-20250514"
    const val CLAUDE_HAIKU = "claude-haiku-4-5-20251001"
    const val GPT_4O = "gpt-4o"
    const val GPT_4O_MINI = "gpt-4o-mini"
    const val GEMINI_2_FLASH = "gemini-2.0-flash"
    const val GEMINI_2_PRO = "gemini-2.0-pro"
    const val GROK_3 = "grok-3"
    const val GROK_3_MINI = "grok-3-mini"
    const val DEEPSEEK_V3 = "deepseek-chat"
    const val DEEPSEEK_R1 = "deepseek-reasoner"
    const val MISTRAL_LARGE = "mistral-large-latest"
    const val MISTRAL_SMALL = "mistral-small-latest"

    // Groq-hosted models
    const val GROQ_LLAMA_3_70B = "groq-llama-3.3-70b"

    // Cohere models
    const val COMMAND_R_PLUS = "command-r-plus"
    const val COMMAND_R = "command-r"

    // Perplexity models
    const val SONAR_PRO = "sonar-pro"
    const val SONAR = "sonar"
}
