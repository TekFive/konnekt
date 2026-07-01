package org.tekfive.konnekt.message.email.providers.zeptomail

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.konnekt.HttpStatusException
import org.tekfive.konnekt.message.MessageHttpClient
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailEmailStatusResponse
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailSendRequest
import org.tekfive.konnekt.message.email.providers.zeptomail.model.ZeptoMailSendResponse

/**
 * Minimal ZeptoMail client for the send-mail and email-status APIs.
 */
open class ZeptoMailClient(
    private val auth: ZeptoMailConfiguration,
    private val client: OkHttpClient = MessageHttpClient.client,
    private val executeOverride: ((Request) -> Response)? = null,
) {

    open fun sendMail(requestBody: ZeptoMailSendRequest): ZeptoMailSendResponse {
        val request = post("v1.1", "email", body = requestBody.toJsonString())
        execute(request, "ZeptoMail send failed").use { response ->
            val responseBody = response.body?.string()?.trim()
            if (responseBody.isNullOrBlank()) {
                return ZeptoMailSendResponse(status = "success")
            }

            val json = responseBody.asRequiredJsonObject()
            return ZeptoMailSendResponse(
                requestId = extractRequestId(json),
                status = json.string("status") ?: json.string("message"),
            )
        }
    }

    open fun getEmailStatus(emailReference: String): ZeptoMailEmailStatusResponse? {
        val normalizedReference = emailReference.trim()
        if (normalizedReference.isBlank()) {
            return null
        }
        if (auth.oauthAuthorizationHeader == null) {
            return null
        }

        val request = get("v1.1", "email", "email-reference", normalizedReference)
        return executeOrNull(request, "ZeptoMail status lookup failed")?.use { response ->
            val responseBody = response.body?.string()
            if (responseBody.isNullOrBlank()) {
                null
            } else {
                val json = responseBody.asRequiredJsonObject()
                parseEmailStatusResponse(json)
            }
        }
    }

    internal fun get(vararg pathSegments: String): Request {
        val authorizationHeader = auth.oauthAuthorizationHeader
            ?: error("ZeptoMail OAuth access token is required for status lookup.")
        val url = buildUrl(pathSegments.toList())
        return Request.Builder()
            .url(url)
            .header("Authorization", authorizationHeader)
            .header("Accept", "application/json")
            .get()
            .build()
    }

    internal fun post(vararg pathSegments: String, body: String): Request {
        val url = buildUrl(pathSegments.toList())
        return Request.Builder()
            .url(url)
            .header("Authorization", auth.sendAuthorizationHeader)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildUrl(pathSegments: List<String>): String {
        val builder = auth.normalizedBaseUrl.toHttpUrl().newBuilder()
        pathSegments.forEach { segment ->
            builder.addPathSegment(segment)
        }
        return builder.build().toString()
    }

    private fun execute(request: Request, errorMessage: String): Response {
        val response = executeRaw(request)
        if (!response.code.isSuccessful()) {
            throw failWithStatus(response, errorMessage)
        }
        return response
    }

    private fun executeOrNull(request: Request, errorMessage: String): Response? {
        val response = executeRaw(request)
        if (response.code == 404) {
            response.close()
            return null
        }
        if (!response.code.isSuccessful()) {
            throw failWithStatus(response, errorMessage)
        }
        return response
    }

    /**
     * Closes the failed [response] and returns a scrubbed exception carrying only the HTTP
     * status code — never the response body, which may echo recipient or message content.
     */
    private fun failWithStatus(response: Response, errorMessage: String): HttpStatusException {
        val statusCode = response.code
        response.close()
        return HttpStatusException(statusCode, null, "$errorMessage with HTTP status $statusCode.")
    }

    private fun executeRaw(request: Request): Response {
        executeOverride?.let { execute ->
            return execute(request)
        }

        return client.newCall(request).execute()
    }

    private fun extractRequestId(json: JsonObject): String? {
        val topLevelRequestId = json.string("request_id")
            ?: json.string("requestId")
        if (!topLevelRequestId.isNullOrBlank()) {
            return topLevelRequestId
        }

        val firstDataObject = json.array("data")
            ?.toReqObjList()
            ?.firstOrNull()
            ?: return null

        return firstDataObject.string("request_id")
            ?: firstDataObject.string("requestId")
            ?: firstDataObject.obj("additional_info")?.string("request_id")
            ?: firstDataObject.obj("additional_info")?.string("requestId")
            ?: firstDataObject.obj("additional_info")?.obj("email_info")?.string("request_id")
            ?: firstDataObject.obj("additional_info")?.obj("email_info")?.string("requestId")
    }

    private fun parseEmailStatusResponse(json: JsonObject): ZeptoMailEmailStatusResponse {
        val dataObject = json.array("data")
            ?.toReqObjList()
            ?.firstOrNull()
            ?: JsonObject()
        val emailInfo = dataObject.obj("email_info")
        val deliveryDetails = dataObject.obj("email_delivery_details")
        val trackingDetails = dataObject.obj("email_tracking_details")
        val emailOpen = trackingDetails?.obj("email_open")

        return ZeptoMailEmailStatusResponse(
            requestId = emailInfo?.string("request_id") ?: json.string("request_id"),
            emailReference = emailInfo?.string("message_id")
                ?: emailInfo?.string("messageId")
                ?: emailInfo?.string("email_reference"),
            status = emailInfo?.string("status"),
            openCount = emailOpen?.get("event_count")?.int ?: 0,
            hasDeliveredRecipients = hasNonEmptyField(deliveryDetails, "delivered"),
            hasHardBounceRecipients = hasNonEmptyField(deliveryDetails, "hardbounce", "hard_bounce"),
            hasSoftBounceRecipients = hasNonEmptyField(deliveryDetails, "softbounce", "soft_bounce"),
            hasMailFailureRecipients = hasNonEmptyField(deliveryDetails, "mailfailure", "mail_failure"),
            hasProcessFailedRecipients = hasNonEmptyField(deliveryDetails, "processfailed", "process_failed"),
        )
    }

    private fun hasNonEmptyField(json: JsonObject?, vararg fieldNames: String): Boolean {
        if (json == null) {
            return false
        }

        return fieldNames.any { fieldName ->
            val arrayValue = json.array(fieldName)
            if (arrayValue != null) {
                return@any arrayValue.items.isNotEmpty()
            }

            val objectValue = json.obj(fieldName)
            if (objectValue != null) {
                return@any true
            }

            val stringValue = json.string(fieldName)
            if (!stringValue.isNullOrBlank()) {
                return@any true
            }

            json.boolean(fieldName) == true
        }
    }

    private fun Int.isSuccessful(): Boolean {
        return this in 200..299
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
