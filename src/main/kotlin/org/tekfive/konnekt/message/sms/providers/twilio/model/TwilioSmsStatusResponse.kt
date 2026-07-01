package org.tekfive.konnekt.message.sms.providers.twilio.model

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonObject

data class TwilioSmsStatusResponse(
    val sid: String? = null,
    val status: String? = null,
    val errorCode: Int? = null,
) {
    companion object : FromJsonObject<TwilioSmsStatusResponse> {

        // Twilio returns snake_case "error_code"; JFK matches constructor parameters by exact
        // name, so map it explicitly (falling back to "errorCode" for round-tripped JSON).
        override fun fromJson(json: JsonObject): TwilioSmsStatusResponse {
            val errorCode = json["error_code"].int ?: json["errorCode"].int
            return fromJson(json, false, TwilioSmsStatusResponse::errorCode to errorCode)
        }
    }
}
