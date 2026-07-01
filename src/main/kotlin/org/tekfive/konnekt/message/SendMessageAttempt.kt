package org.tekfive.konnekt.message

import org.tekfive.keep.data.Data
import org.tekfive.keep.db.dbTransactionAt

class SendMessageAttempt(
    val queuedMessageId: Long,
    var state: SendMessageAttemptState,
    var details: String? = null,
    val startedAt: Long = dbTransactionAt(),
    var endedAt: Long? = null,
) : Data() {
}
