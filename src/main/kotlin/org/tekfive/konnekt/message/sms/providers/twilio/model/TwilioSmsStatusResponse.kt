package org.tekfive.konnekt.message.sms.providers.twilio.model

import org.tekfive.jfk.FromJsonObject

data class TwilioSmsStatusResponse(
    val sid: String? = null,
    val status: String? = null,
    val errorCode: Int? = null,
) {
    companion object : FromJsonObject<TwilioSmsStatusResponse>
}
