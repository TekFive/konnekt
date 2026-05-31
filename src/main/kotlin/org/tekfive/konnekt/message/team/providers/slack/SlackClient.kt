package org.tekfive.konnekt.message.team.providers.slack

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.jfk.json

open class SlackClient(
    private val auth: SlackAuth,
    private val client: OkHttpClient = OkHttpClient(),
    private val executeOverride: ((Request) -> String)? = null,
) {

    open fun postMessage(channel: String, text: String): SlackPostMessageResponse {
        val response = execute(
            post(
                "chat.postMessage",
                json {
                    "channel" set channel
                    "text" set text
                }.toJsonString(),
            )
        )

        val responseChannel = response.string("channel") ?: channel
        val ts = response.string("ts")
            ?: response.obj("message")?.string("ts")
            ?: throw SlackException("Slack chat.postMessage response did not include a message timestamp")

        return SlackPostMessageResponse(responseChannel, ts)
    }

    open fun lookupUserByEmail(email: String): String? {
        val response = executeOrNull(
            get(
                "users.lookupByEmail",
                mapOf("email" to email),
            ),
            ignoredErrors = setOf("users_not_found"),
        ) ?: return null

        return response.obj("user")?.string("id")
    }

    open fun openConversation(userId: String): String? {
        val response = execute(
            post(
                "conversations.open",
                json {
                    "users" set userId
                    "return_im" set true
                }.toJsonString(),
            )
        )

        return response.obj("channel")?.string("id")
    }

    open fun findConversationByName(name: String): String? {
        val normalizedName = name.trim().removePrefix("#")
        if (normalizedName.isBlank()) return null

        var cursor: String? = null
        do {
            val query = mutableMapOf(
                "types" to "public_channel,private_channel",
                "limit" to "1000",
            )
            if (!cursor.isNullOrBlank()) {
                query["cursor"] = cursor
            }

            val response = execute(get("conversations.list", query))
            val match = response.array("channels")
                ?.toReqObjList()
                ?.firstOrNull { channel ->
                    channel.string("name") == normalizedName ||
                        channel.string("name_normalized") == normalizedName ||
                        channel.string("id") == normalizedName
                }

            if (match != null) {
                return match.string("id")
            }

            cursor = response.obj("response_metadata")?.string("next_cursor")?.takeIf { it.isNotBlank() }
        } while (cursor != null)

        return null
    }

    internal fun get(method: String, query: Map<String, String> = emptyMap()): Request {
        val url = buildUrl(method, query)
        return Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader)
            .header("Accept", JSON_MEDIA_TYPE)
            .get()
            .build()
    }

    internal fun post(method: String, body: String): Request {
        val url = buildUrl(method)
        return Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader)
            .header("Accept", JSON_MEDIA_TYPE)
            .header("Content-Type", JSON_MEDIA_TYPE)
            .post(body.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
    }

    private fun buildUrl(method: String, query: Map<String, String> = emptyMap()): String {
        val builder = auth.normalizedBaseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("api")
            .addPathSegment(method)

        query.forEach { (name, value) ->
            builder.addQueryParameter(name, value)
        }

        return builder.build().toString()
    }

    private fun execute(request: Request): JsonObject {
        return executeOrNull(request, ignoredErrors = emptySet())
            ?: throw SlackException("Slack request failed without response details")
    }

    private fun executeOrNull(request: Request, ignoredErrors: Set<String>): JsonObject? {
        val body = executeBody(request)
        val response = body.asRequiredJsonObject()
        if (response.boolean("ok") != false) {
            return response
        }

        val error = response.string("error") ?: "unknown_error"
        if (error in ignoredErrors) {
            return null
        }

        throw SlackException("Slack request failed: $error")
    }

    private fun executeBody(request: Request): String {
        executeOverride?.let { return it(request) }

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw SlackException("Slack request failed with ${response.code}: $body")
            }
            return body
        }
    }

    companion object {
        private const val JSON_MEDIA_TYPE = "application/json"
    }
}
