package org.tekfive.konnekt.llm.utils

import okhttp3.OkHttpClient
import org.tekfive.konnekt.llm.LlmEndpoint
import java.io.ByteArrayInputStream
import java.net.MalformedURLException
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal class OkHttpClientPool {

    private var httpClientsByKey = mutableMapOf<HttpClientKey, HttpClientHolder>()


    @Throws(MalformedURLException::class)
    fun getClient(llmEndpoint: LlmEndpoint): OkHttpClient {
        val pinnedCertificate = llmEndpoint.pinnedCertificate?.ifBlank { null }
        val clientKey = HttpClientKey(URL(llmEndpoint.baseUrl), pinnedCertificate)
        var httpClient = httpClientsByKey[clientKey]?.get()
        val connectionTimeoutSeconds = llmEndpoint.connectionTimeoutSeconds ?: DEFAULT_CONNECTION_TIMEOUT_SECONDS
        val readTimeoutSeconds = llmEndpoint.readTimeoutSeconds ?: DEFAULT_READ_TIMEOUT_SECONDS
        if (httpClient == null ||
            httpClient.connectTimeoutMillis != connectionTimeoutSeconds * 1000 ||
            httpClient.readTimeoutMillis != readTimeoutSeconds * 1000) {

            val builder = OkHttpClient.Builder()
                .connectTimeout(connectionTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds.toLong(), TimeUnit.SECONDS)

            if (!pinnedCertificate.isNullOrBlank()) {
                val cf: CertificateFactory = CertificateFactory.getInstance("X.509")
                val certs = cf.generateCertificates(ByteArrayInputStream(pinnedCertificate.toByteArray()))

                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                keyStore.load(null, null)
                certs.forEachIndexed { index, cert ->
                    keyStore.setCertificateEntry("ca-$index", cert)
                }

                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(keyStore)

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, tmf.getTrustManagers(), null)


                // Trust only the pinned CA, but keep OkHttp's default hostname verification
                builder.sslSocketFactory(sslContext.getSocketFactory(), tmf.trustManagers[0] as X509TrustManager)
            }

            httpClient = builder.build()
            httpClientsByKey[clientKey] = HttpClientHolder(httpClient)
        }

        return httpClient
    }

    companion object {
        const val DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30

        const val DEFAULT_READ_TIMEOUT_SECONDS = 120
    }
}

private data class HttpClientKey(val ssl: Boolean, val host: String, val trustedCertificate: String?) {
    constructor(apiEndpointURL: URL, trustedCertificate: String?) : this(apiEndpointURL.protocol.equals("https", ignoreCase = true), apiEndpointURL.host, trustedCertificate)
}

private class HttpClientHolder(val client: OkHttpClient) {
    var lastAccessAt: Long = System.currentTimeMillis()
        private set

    fun get(): OkHttpClient {
        lastAccessAt = System.currentTimeMillis()
        return client
    }
}