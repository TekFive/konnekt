package org.tekfive.konnekt.llm.integration

import org.junit.jupiter.api.Assumptions.assumeTrue

object ProviderIntegrationTestSupport {

    fun requiredEnv(name: String): String {
        val value = System.getenv(name)
        assumeTrue(!value.isNullOrBlank(), "Missing required environment variable: $name")
        return value
    }

    fun optionalEnv(name: String): String? {
        return System.getenv(name)?.takeIf { it.isNotBlank() }
    }

    fun longEnv(name: String, defaultValue: Long): Long {
        return System.getenv(name)?.toLongOrNull() ?: defaultValue
    }
}
