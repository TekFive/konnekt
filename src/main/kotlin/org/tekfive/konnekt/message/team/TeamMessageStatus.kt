package org.tekfive.konnekt.message.team

enum class TeamMessageStatus {
    QUEUED,
    SENT,
    DELIVERED,
    READ,
    FAILED,

    /** The provider does not support status lookup, or returned a status this library does not recognize. */
    UNKNOWN,
}
