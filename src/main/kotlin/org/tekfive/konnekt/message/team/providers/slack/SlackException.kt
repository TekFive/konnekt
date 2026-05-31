package org.tekfive.konnekt.message.team.providers.slack

class SlackException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
