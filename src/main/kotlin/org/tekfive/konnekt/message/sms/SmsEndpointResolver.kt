package org.tekfive.konnekt.message.sms

fun interface SmsEndpointResolver {
    fun resolve(endpointId: String): SmsEndpoint?
}
