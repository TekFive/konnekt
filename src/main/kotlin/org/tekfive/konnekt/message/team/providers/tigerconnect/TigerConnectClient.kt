package org.tekfive.konnekt.message.team.providers.tigerconnect

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.jfk.fromJson
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectDistributionListLookupResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectGroupLookupResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectMessageStatusResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectRoleLookupResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectSendRequest
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectSendResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectUserLookupResponse

open class TigerConnectClient(
    private val auth: TigerConnectAuth,
    private val client: OkHttpClient = OkHttpClient(),
    private val executeOverride: ((Request) -> String)? = null,
) {

    open fun findUserByEmail(email: String): TigerConnectUserLookupResponse {
        val request = get("/users", mapOf("email" to email))
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectUserLookupResponse)
    }

    open fun findGroupByName(name: String): TigerConnectGroupLookupResponse {
        val request = get("/groups", mapOf("name" to name))
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectGroupLookupResponse)
    }

    open fun findRoleByName(name: String): TigerConnectRoleLookupResponse {
        val request = get("/roles", mapOf("name" to name))
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectRoleLookupResponse)
    }

    open fun findDistributionListByName(name: String): TigerConnectDistributionListLookupResponse {
        val request = get("/distribution-lists", mapOf("name" to name))
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectDistributionListLookupResponse)
    }

    open fun sendMessage(requestBody: TigerConnectSendRequest): TigerConnectSendResponse {
        val request = post("/message", requestBody.toJsonString())
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectSendResponse)
    }

    open fun getMessageStatus(messageId: String): TigerConnectMessageStatusResponse {
        val request = get("/message/$messageId/status")
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectMessageStatusResponse)
    }

    internal fun get(path: String, query: Map<String, String> = emptyMap()): Request {
        val url = buildUrl(path, query)
        return Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader)
            .header("Accept", "application/json")
            .get()
            .build()
    }

    internal fun post(path: String, body: String): Request {
        val url = buildUrl(path)
        return Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildUrl(path: String, query: Map<String, String> = emptyMap()): String {
        val base = auth.normalizedBaseUrl
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        if (query.isEmpty()) {
            return "$base$normalizedPath"
        }

        val queryString = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        return "$base$normalizedPath?$queryString"
    }

    private fun execute(request: Request): String {
        executeOverride?.let { return it(request) }

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TigerConnectException("TigerConnect request failed with ${response.code}: $body")
            }
            return body
        }
    }
}
