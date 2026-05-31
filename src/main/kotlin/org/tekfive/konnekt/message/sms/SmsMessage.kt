package org.tekfive.konnekt.message.sms

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.jfk.ToJsonObject
import org.tekfive.konnekt.message.MessageAddress

class SmsMessage(
    val to: List<MessageAddress>,
    val from: MessageAddress,
    val body: String,
) : ToJsonObject {

    override fun toJsonObject(): JsonObject {
        return json {
            "to" set to
            "from" set from
            "body" set body
        }
    }

    companion object : FromJsonObject<SmsMessage>
}
