package org.tekfive.konnekt.message.team.providers.tigerconnect

class TigerConnectException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
