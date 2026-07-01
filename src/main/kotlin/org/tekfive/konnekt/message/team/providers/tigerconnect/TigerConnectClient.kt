package org.tekfive.konnekt.message.team.providers.tigerconnect

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.jfk.fromJson
import org.tekfive.konnekt.message.MessageHttpClient
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectDistributionListLookupResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectGroupLookupResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectMessageStatusResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectRoleLookupResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectSendRequest
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectSendResponse
import org.tekfive.konnekt.message.team.providers.tigerconnect.model.TigerConnectUserLookupResponse

open class TigerConnectClient(
    private val auth: TigerConnectAuth,
    private val client: OkHttpClient = MessageHttpClient.client,
    private val executeOverride: ((Request) -> String)? = null,
) {

    open fun findUserByEmail(email: String): TigerConnectUserLookupResponse {
        val request = get(listOf("users"), mapOf("email" to email))
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectUserLookupResponse)
    }

    open fun findGroupByName(name: String): TigerConnectGroupLookupResponse {
        val request = get(listOf("groups"), mapOf("name" to name))
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectGroupLookupResponse)
    }

    open fun findRoleByName(name: String): TigerConnectRoleLookupResponse {
        val request = get(listOf("roles"), mapOf("name" to name))
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectRoleLookupResponse)
    }

    open fun findDistributionListByName(name: String): TigerConnectDistributionListLookupResponse {
        val request = get(listOf("distribution-lists"), mapOf("name" to name))
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectDistributionListLookupResponse)
    }

    open fun sendMessage(requestBody: TigerConnectSendRequest): TigerConnectSendResponse {
        val request = post(listOf("message"), requestBody.toJsonString())
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectSendResponse)
    }

    open fun getMessageStatus(messageId: String): TigerConnectMessageStatusResponse {
        val request = get(listOf("message", messageId, "status"))
        return execute(request).asRequiredJsonObject().fromJson(TigerConnectMessageStatusResponse)
    }

    internal fun get(pathSegments: List<String>, query: Map<String, String> = emptyMap()): Request {
        val url = buildUrl(pathSegments, query)
        return Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader)
            .header("Accept", "application/json")
            .get()
            .build()
    }

    internal fun post(pathSegments: List<String>, body: String): Request {
        val url = buildUrl(pathSegments)
        return Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildUrl(pathSegments: List<String>, query: Map<String, String> = emptyMap()): String {
        val builder = auth.normalizedBaseUrl.toHttpUrl().newBuilder()

        for (segment in pathSegments) {
            builder.addPathSegment(segment)
        }

        query.forEach { (name, value) ->
            builder.addQueryParameter(name, value)
        }

        return builder.build().toString()
    }

    private fun execute(request: Request): String {
        executeOverride?.let { return it(request) }

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Never include the response body — it may contain PHI or credentials.
                throw TigerConnectException(
                    "TigerConnect request failed with HTTP status ${response.code}",
                    statusCode = response.code,
                )
            }
            return body
        }
    }
}
