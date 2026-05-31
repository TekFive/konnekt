package org.tekfive.konnekt.message.email.providers.twilio.model

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.json

data class TwilioSendGridMailSendRequest(
    val personalizations: List<TwilioSendGridPersonalization>,
    val from: TwilioSendGridEmailAddress,
    val subject: String? = null,
    val content: List<TwilioSendGridContent>,
) : ToJsonObject {

    override fun toJsonObject(): JsonObject {
        return json {
            "personalizations" set personalizations
            "from" set from
            if (subject != null) {
                "subject" set subject
            }
            "content" set content
        }
    }
}

data class TwilioSendGridPersonalization(
    val to: List<TwilioSendGridEmailAddress>,
    val cc: List<TwilioSendGridEmailAddress> = emptyList(),
    val bcc: List<TwilioSendGridEmailAddress> = emptyList(),
) : ToJsonObject {
    override fun toJsonObject(): JsonObject {
        return json {
            "to" set to
            if (cc.isNotEmpty()) {
                "cc" set cc
            }
            if (bcc.isNotEmpty()) {
                "bcc" set bcc
            }
        }
    }
}

data class TwilioSendGridEmailAddress(
    val email: String,
    val name: String? = null,
) : ToJsonObject {
    override fun toJsonObject(): JsonObject {
        return json {
            "email" set email
            if (name != null) {
                "name" set name
            }
        }
    }
}

data class TwilioSendGridContent(
    val type: String,
    val value: String,
) : ToJsonObject {
    override fun toJsonObject(): JsonObject {
        return json {
            "type" set type
            "value" set value
        }
    }
}
