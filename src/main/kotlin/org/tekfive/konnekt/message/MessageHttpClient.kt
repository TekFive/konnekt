package org.tekfive.konnekt.message

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP client for message provider integrations.
 *
 * A single [OkHttpClient] shares one connection pool and dispatcher across all providers
 * (OkHttp's documented best practice) instead of allocating a new pool per send. Providers
 * needing different settings should derive from [client] with [OkHttpClient.newBuilder],
 * which reuses the pool and dispatcher.
 */
object MessageHttpClient {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
}
