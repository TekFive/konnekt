package org.tekfive.konnekt.message

open class MessagingException(val recoverable: Boolean, message: String, override val cause: Throwable? = null) : Exception(message, cause) {
}