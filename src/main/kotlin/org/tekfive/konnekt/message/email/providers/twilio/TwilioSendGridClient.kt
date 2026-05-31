package org.tekfive.konnekt.message.email.providers.twilio

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.jfk.JsonObject
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridEmailActivityResponse
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridEmailEvent
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridMailSendRequest
import org.tekfive.konnekt.message.email.providers.twilio.model.TwilioSendGridMailSendResponse

open class TwilioSendGridClient(
    private val auth: TwilioSendGridConfiguration,
    private val client: OkHttpClient = OkHttpClient(),
    private val executeOverride: ((Request) -> TwilioSendGridRawResponse)? = null,
) {

    open fun sendMail(requestBody: TwilioSendGridMailSendRequest): TwilioSendGridMailSendResponse {
        val request = post("v3", "mail", "send", body = requestBody.toJsonString())
        val response = execute(request)
        val messageId = extractMessageId(response)
        val status = when (response.code) {
            202 -> "queued"
            200, 201 -> "sent"
            else -> null
        }

        return TwilioSendGridMailSendResponse(
            messageId = messageId,
            status = status,
        )
    }

    open fun getEmailActivity(messageId: String): TwilioSendGridEmailActivityResponse? {
        val normalizedMessageId = messageId.trim()
        if (normalizedMessageId.isBlank()) {
            return null
        }

        val request = get(
            "v3",
            "messages",
            query = mapOf(
                "query" to buildActivityQuery(normalizedMessageId),
            ),
        )
        val response = executeOrNull(request) ?: return null
        return parseActivityResponse(response, normalizedMessageId)
    }

    internal fun get(vararg pathSegments: String, query: Map<String, String> = emptyMap()): Request {
        val url = buildUrl(pathSegments.toList(), query)
        return Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader)
            .header("Accept", "application/json")
            .get()
            .build()
    }

    internal fun post(vararg pathSegments: String, body: String): Request {
        val url = buildUrl(pathSegments.toList())
        return Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildUrl(pathSegments: List<String>, query: Map<String, String> = emptyMap()): String {
        val builder = auth.normalizedBaseUrl.toHttpUrl().newBuilder()
        pathSegments.forEach { segment ->
            builder.addPathSegment(segment)
        }
        query.forEach { (name, value) ->
            builder.addQueryParameter(name, value)
        }
        return builder.build().toString()
    }

    private fun execute(request: Request): TwilioSendGridRawResponse {
        val response = executeRaw(request)
        if (!isSuccessfulSendResponse(response.code)) {
            throw TwilioSendGridException("SendGrid request failed with ${response.code}: ${response.body}")
        }

        return response
    }

    private fun executeOrNull(request: Request): TwilioSendGridRawResponse? {
        val response = executeRaw(request)
        if (response.code == 404 || response.code == 403) {
            return null
        }

        if (!isSuccessfulActivityResponse(response.code)) {
            throw TwilioSendGridException("SendGrid activity lookup failed with ${response.code}: ${response.body}")
        }

        return response
    }

    private fun executeRaw(request: Request): TwilioSendGridRawResponse {
        executeOverride?.let { return it(request) }

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val headers = response.headers.toMultimap().mapValues { entry -> entry.value.firstOrNull().orEmpty() }
            return TwilioSendGridRawResponse(
                code = response.code,
                body = body,
                headers = headers,
            )
        }
    }

    private fun isSuccessfulSendResponse(code: Int): Boolean {
        return code in 200..299
    }

    private fun isSuccessfulActivityResponse(code: Int): Boolean {
        return code in 200..299
    }

    private fun buildActivityQuery(messageId: String): String {
        val escapedMessageId = messageId
            .replace("\\", "\\\\")
            .replace("'", "\\'")

        return "msg_id LIKE '$escapedMessageId%'"
    }

    private fun extractMessageId(response: TwilioSendGridRawResponse): String {
        val headerMessageId = response.headers.entries
            .firstOrNull { it.key.equals("X-Message-Id", ignoreCase = true) }
            ?.value

        if (!headerMessageId.isNullOrBlank()) {
            return headerMessageId
        }

        val body = response.body.trim()
        if (body.isBlank()) {
            return ""
        }

        val json = body.asRequiredJsonObject()
        return json.string("message_id")
            ?: json.string("messageId")
            ?: json.string("id")
            ?: ""
    }

    private fun parseActivityResponse(
        response: TwilioSendGridRawResponse,
        requestedTrackingId: String,
    ): TwilioSendGridEmailActivityResponse {
        val json = response.body.asRequiredJsonObject()
        val activityJson = selectActivityRecord(json, requestedTrackingId)

        val messageId: String? = activityJson.string("msg_id")
            ?: activityJson.string("msgId")
            ?: activityJson.string("message_id")
            ?: activityJson.string("messageId")
            ?: activityJson.string("id")

        val status: String? = activityJson.string("status")
        val events: List<TwilioSendGridEmailEvent> = activityJson.array("events")
            ?.toReqObjList()
            ?.mapNotNull { eventObject ->
                val eventName = eventObject.string("event_name")
                    ?: eventObject.string("event")
                    ?: eventObject.string("status")
                if (eventName.isNullOrBlank()) {
                    null
                } else {
                    TwilioSendGridEmailEvent(
                        event_name = eventName,
                        status = eventObject.string("status"),
                        timestamp = eventObject.string("timestamp"),
                    )
                }
            }
            ?: emptyList()

        return TwilioSendGridEmailActivityResponse(
            messageId = messageId,
            status = status,
            events = events,
        )
    }

    private fun selectActivityRecord(
        json: JsonObject,
        requestedTrackingId: String,
    ): JsonObject {
        val messageRecords = json.array("messages")
            ?.toReqObjList()
            .orEmpty()

        if (messageRecords.isEmpty()) {
            return json
        }

        val candidates = messageRecords.mapIndexedNotNull { index, record ->
            val messageId = extractActivityMessageId(record) ?: return@mapIndexedNotNull null

            ActivityRecordCandidate(
                index = index,
                messageId = messageId,
                record = record,
            )
        }

        if (candidates.isEmpty()) {
            return messageRecords.first()
        }

        val exactMatch = candidates.firstOrNull { candidate ->
            candidate.messageId == requestedTrackingId
        }

        if (exactMatch != null) {
            return exactMatch.record
        }

        val prefixMatches = candidates.filter { candidate ->
            candidate.messageId.startsWith(requestedTrackingId)
        }

        if (prefixMatches.isNotEmpty()) {
            return selectDeterministicCandidate(prefixMatches)
        }

        return selectDeterministicCandidate(candidates)
    }

    private fun extractActivityMessageId(activityJson: JsonObject): String? {
        return activityJson.string("msg_id")
            ?: activityJson.string("msgId")
            ?: activityJson.string("message_id")
            ?: activityJson.string("messageId")
            ?: activityJson.string("id")
    }

    private fun selectDeterministicCandidate(candidates: List<ActivityRecordCandidate>): JsonObject {
        return candidates.maxWith(
            compareBy<ActivityRecordCandidate> { candidate -> candidate.messageId.length }
                .thenByDescending { candidate -> candidate.messageId }
                .thenByDescending { candidate -> candidate.index },
        ).record
    }

    data class TwilioSendGridRawResponse(
        val code: Int,
        val body: String,
        val headers: Map<String, String>,
    )

    private data class ActivityRecordCandidate(
        val index: Int,
        val messageId: String,
        val record: JsonObject,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
