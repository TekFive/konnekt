package org.tekfive.konnekt.message

import org.tekfive.keep.data.Data

class SendMessageAttempt(
    val queuedMessageId: Long,
    var state: SendMessageAttemptState,
    var details: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    var endedAt: Long? = null,
) : Data() {
}
