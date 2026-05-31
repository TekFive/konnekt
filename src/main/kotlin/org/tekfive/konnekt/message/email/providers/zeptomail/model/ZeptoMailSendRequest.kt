package org.tekfive.konnekt.message.email.providers.zeptomail.model

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.json

data class ZeptoMailSendRequest(
    val from: ZeptoMailEmailAddress,
    val to: List<ZeptoMailRecipient>,
    val cc: List<ZeptoMailRecipient> = emptyList(),
    val bcc: List<ZeptoMailRecipient> = emptyList(),
    val subject: String? = null,
    val textBody: String? = null,
    val htmlBody: String? = null,
    val trackOpens: Boolean? = null,
    val trackClicks: Boolean? = null,
    val bounceAddress: String? = null,
) : ToJsonObject {

    override fun toJsonObject(): JsonObject {
        return json {
            "from" set from
            "to" set to
            if (cc.isNotEmpty()) {
                "cc" set cc
            }
            if (bcc.isNotEmpty()) {
                "bcc" set bcc
            }
            if (subject != null) {
                "subject" set subject
            }
            if (textBody != null) {
                "textbody" set textBody
            }
            if (htmlBody != null) {
                "htmlbody" set htmlBody
            }
            if (trackOpens != null) {
                "track_opens" set trackOpens
            }
            if (trackClicks != null) {
                "track_clicks" set trackClicks
            }
            if (bounceAddress != null) {
                "bounce_address" set bounceAddress
            }
        }
    }
}

data class ZeptoMailEmailAddress(
    val address: String,
    val name: String? = null,
) : ToJsonObject {

    override fun toJsonObject(): JsonObject {
        return json {
            "address" set address
            if (name != null) {
                "name" set name
            }
        }
    }
}

data class ZeptoMailRecipient(
    val emailAddress: ZeptoMailEmailAddress,
) : ToJsonObject {

    override fun toJsonObject(): JsonObject {
        return json {
            "email_address" set emailAddress
        }
    }
}
