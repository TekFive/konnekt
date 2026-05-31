package org.tekfive.konnekt.llm

data class LlmEndpoint(
    val providerType: LlmServiceProviderType,
    val model: String? = null,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val pinnedCertificate: String? = null,
    val connectionTimeoutSeconds: Int? = null,
    val readTimeoutSeconds: Int? = null,
) {
    val resolvedBaseUrl: String?
        get() = baseUrl ?: providerType.defaultBaseUrl
}
