package org.tekfive.konnekt.message.sms.providers.twilio

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.konnekt.message.MessageHttpClient
import org.tekfive.konnekt.message.sms.providers.twilio.model.TwilioSmsSendRequest
import org.tekfive.konnekt.message.sms.providers.twilio.model.TwilioSmsSendResponse
import org.tekfive.konnekt.message.sms.providers.twilio.model.TwilioSmsStatusResponse

/**
 * Minimal Twilio Programmable Messaging client built on raw OkHttp calls.
 */
open class TwilioSmsClient(
    private val auth: TwilioSmsAuth,
    private val client: OkHttpClient = MessageHttpClient.client,
    private val executeOverride: ((Request) -> TwilioSmsRawResponse)? = null,
) {

    open fun send(requestBody: TwilioSmsSendRequest): TwilioSmsSendResponse {
        val request = post(
            "2010-04-01",
            "Accounts",
            auth.accountSid,
            "Messages.json",
            body = requestBody,
        )
        val response = execute(request)
        if (!response.code.isSuccessful()) {
            // Never include the response body — Twilio error bodies echo the recipient phone number.
            throw TwilioSmsException("Twilio SMS send failed with status ${response.code}", response.code)
        }

        val json = response.body.trim().takeIf { it.isNotBlank() }?.asRequiredJsonObject()
        val sendResponse = if (json != null) {
            TwilioSmsSendResponse.fromJson(json)
        } else {
            TwilioSmsSendResponse()
        }
        if (sendResponse.resolvedMessageId == null) {
            throw TwilioSmsException("Twilio SMS send succeeded without returning a message SID")
        }
        return sendResponse
    }

    open fun getMessageStatus(messageId: String): TwilioSmsStatusResponse? {
        val normalizedMessageId = messageId.trim()
        if (normalizedMessageId.isBlank()) {
            return null
        }

        val request = get(
            "2010-04-01",
            "Accounts",
            auth.accountSid,
            "Messages",
            "$normalizedMessageId.json",
        )
        val response = executeOrNull(request) ?: return null
        val json = response.body.trim().takeIf { it.isNotBlank() }?.asRequiredJsonObject()
            ?: return TwilioSmsStatusResponse()
        return TwilioSmsStatusResponse.fromJson(json)
    }

    internal fun get(vararg pathSegments: String): Request {
        val url = buildUrl(pathSegments.toList())
        return Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader)
            .header("Accept", "application/json")
            .get()
            .build()
    }

    internal fun post(vararg pathSegments: String, body: TwilioSmsSendRequest): Request {
        val url = buildUrl(pathSegments.toList())
        val formBuilder = FormBody.Builder()
            .add("To", body.to)
        body.from?.let { from ->
            formBuilder.add("From", from)
        }
        formBuilder.add("Body", body.body)
        body.messagingServiceSid?.let { messagingServiceSid ->
            formBuilder.add("MessagingServiceSid", messagingServiceSid)
        }
        val formBody = formBuilder.build()

        return Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader)
            .header("Accept", "application/json")
            .post(formBody)
            .build()
    }

    private fun buildUrl(pathSegments: List<String>): String {
        val builder = auth.normalizedBaseUrl.toHttpUrl().newBuilder()
        pathSegments.forEach { segment ->
            builder.addPathSegment(segment)
        }
        return builder.build().toString()
    }

    private fun execute(request: Request): TwilioSmsRawResponse {
        executeOverride?.let { return it(request) }

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val headers = response.headers.toMultimap().mapValues { entry -> entry.value.firstOrNull().orEmpty() }
            return TwilioSmsRawResponse(
                code = response.code,
                body = body,
                headers = headers,
            )
        }
    }

    private fun executeOrNull(request: Request): TwilioSmsRawResponse? {
        val response = execute(request)
        if (response.code == 404) {
            return null
        }

        if (!response.code.isSuccessful()) {
            // Never include the response body — Twilio error bodies echo the recipient phone number.
            throw TwilioSmsException("Twilio SMS status lookup failed with status ${response.code}", response.code)
        }

        return response
    }

    private fun Int.isSuccessful(): Boolean {
        return this in 200..299
    }

    data class TwilioSmsRawResponse(
        val code: Int,
        val body: String,
        val headers: Map<String, String>,
    )
}
