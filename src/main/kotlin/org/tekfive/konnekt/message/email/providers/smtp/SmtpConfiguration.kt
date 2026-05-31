package org.tekfive.konnekt.message.email.providers.smtp

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

data class SmtpConfiguration(
    val host: String,
    val port: Int?,
    val startTls: Boolean?,
    val sslEnabled: Boolean?,
    val connectionTimeoutMSecs: Int?,
    val timeoutMSecs: Int?,
    val writeTimeoutMSecs: Int?,
    val authenticate: Boolean,
    val username: String?,
    val password: String?
) : ToJsonObject {

    companion object : FromJsonObject<SmtpConfiguration>

    val shouldAuthenticate: Boolean
        get() = authenticate && !username.isNullOrBlank() && !password.isNullOrBlank()
}