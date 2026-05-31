package org.tekfive.konnekt.llm.providers

import org.tekfive.konnekt.llm.LlmServiceProviderType

interface LlmServiceProvider {
    val type: LlmServiceProviderType
        get() = LlmServiceProviderType.entries.first { it.serviceProvider == this }
}