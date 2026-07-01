package org.tekfive.konnekt.message

open class MessagingException(val recoverable: Boolean, message: String, override val cause: Throwable? = null) : Exception(message, cause) {

    companion object {

        /**
         * Whether an HTTP status returned by a message provider represents a transient failure
         * that is safe to retry: request timeout, rate limiting, or a server-side error.
         */
        fun isRecoverableStatus(statusCode: Int): Boolean {
            return statusCode == 408 || statusCode == 429 || statusCode >= 500
        }
    }
}
