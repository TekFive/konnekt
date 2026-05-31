package org.tekfive.konnekt.message

import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType

enum class MessageType(override val id: Int, override val displayName: String) : DataEnum {
    EMAIL(1, "Email"),
    SMS(2, "SMS"),
    TEAM_MESSAGE(3, "Team Message"),
    ;
    companion object : DataEnumColumnType<MessageType>()
}
