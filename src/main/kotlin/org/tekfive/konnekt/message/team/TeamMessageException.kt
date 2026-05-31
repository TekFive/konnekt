package org.tekfive.konnekt.message.team

import org.tekfive.konnekt.message.MessagingException

class TeamMessageException(
    message: String,
    recoverable: Boolean = false,
    cause: Throwable? = null,
) : MessagingException(recoverable, message, cause)
