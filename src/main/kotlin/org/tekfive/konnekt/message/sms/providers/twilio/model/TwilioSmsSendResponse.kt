package org.tekfive.konnekt.message.sms.providers.twilio.model

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonObject

data class TwilioSmsSendResponse(
    val sid: String? = null,
    val status: String? = null,
    val errorCode: Int? = null,
) {
    val resolvedMessageId: String?
        get() = sid?.takeIf { it.isNotBlank() }

    companion object : FromJsonObject<TwilioSmsSendResponse> {

        // Twilio returns snake_case "error_code"; JFK matches constructor parameters by exact
        // name, so map it explicitly (falling back to "errorCode" for round-tripped JSON).
        override fun fromJson(json: JsonObject): TwilioSmsSendResponse {
            val errorCode = json["error_code"].int ?: json["errorCode"].int
            return fromJson(json, false, TwilioSmsSendResponse::errorCode to errorCode)
        }
    }
}
