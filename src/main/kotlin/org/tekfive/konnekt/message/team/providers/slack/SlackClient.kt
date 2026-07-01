package org.tekfive.konnekt.message.team.providers.slack

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessageHttpClient

open class SlackClient(
    private val auth: SlackAuth,
    private val client: OkHttpClient = MessageHttpClient.client,
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
        // Slack channel names are always lowercase; normalize before comparing.
        val normalizedName = name.trim().removePrefix("#").lowercase()
        if (normalizedName.isBlank()) return null

        var cursor: String? = null
        do {
            val query = mutableMapOf(
                "types" to "public_channel,private_channel",
                "exclude_archived" to "true",
                "limit" to "1000",
            )
            if (!cursor.isNullOrBlank()) {
                query["cursor"] = cursor
            }

            val response = execute(get("conversations.list", query))
            val match = response.array("channels")
                ?.toReqObjList()
                ?.firstOrNull { channel ->
                    channel.string("name")?.lowercase() == normalizedName ||
                        channel.string("name_normalized")?.lowercase() == normalizedName ||
                        channel.string("id")?.equals(normalizedName, ignoreCase = true) == true
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
        // A missing "ok" field is an error, not a success — require an explicit true.
        if (response.boolean("ok") == true) {
            return response
        }

        // The Slack "error" code is a stable enum-like token (e.g. "channel_not_found") and is
        // safe to surface; the response body itself must never appear in an exception message.
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
                // Never include the response body — it may contain PHI or credentials.
                throw SlackException("Slack request failed with HTTP status ${response.code}", statusCode = response.code)
            }
            return body
        }
    }

    companion object {
        private const val JSON_MEDIA_TYPE = "application/json"
    }
}
