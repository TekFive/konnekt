package org.tekfive.konnekt.message.team

fun interface TeamMessageEndpointResolver {
    fun resolve(endpointId: String): TeamMessageEndpoint?
}
