package org.tekfive.konnekt.message.team

import org.tekfive.jfk.JsonObject

/**
 * Stateless sender interface for secure messaging providers. Each implementation reads
 * connection credentials from the caller-supplied config JsonObject rather than
 * application-level properties.
 */
interface TeamMessageSender {

    val capabilities: Set<TeamMessageCapability>

    fun send(message: TeamMessage, config: JsonObject): TeamMessageResponse

    fun status(messageId: String, config: JsonObject): TeamMessageStatus
}
