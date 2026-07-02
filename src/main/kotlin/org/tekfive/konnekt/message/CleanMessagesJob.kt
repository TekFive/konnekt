package org.tekfive.konnekt.message

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.tekfive.ack.Ack
import org.tekfive.ack.ackNamespace
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.db.db
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobResult
import org.tekfive.keep.job.db.QueryNode
import org.tekfive.keep.job.schedule.FixedIntervalJobSpec
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days

/**
 * Periodically deletes processed (terminal-state) queued messages from [QueuedMessageTable].
 *
 * Retention is split by outcome: [QueuedMessageState.SENT] messages are kept for
 * [sentKeepDaysAck] days, while the failed terminal states ([QueuedMessageState.FAILED],
 * [QueuedMessageState.TIMED_OUT], [QueuedMessageState.CANCELLED]) are kept for
 * [failedKeepDaysAck] days, which defaults to the sent keep time when not configured.
 * Age is measured from [QueuedMessageTable.lastStateChangeAt] — when the message reached its
 * terminal state. Deleting a queued message cascades to its message receipts and send attempts.
 */
class CleanMessagesJob : Job {

    companion object : FixedIntervalJobSpec {
        override val estimateRuntime = false
        override val jobPriority: Int? = null
        override val maxRetriesOnFailure: Int = 0
        override val minSecondsBetweenRetries: Int? = null
        override val retryExceptionBaseTypes: List<KClass<out Exception>> = emptyList()

        override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()

        override fun createJob(): Job = CleanMessagesJob()

        override val intervalSecondsProperty: Ack<Long>
            get() = Ack.long("FIXED_INTERVAL_SECONDS", 24L * 60 * 60, namespace = ackNamespace(getNamespaceClass()), description = "Interval in seconds between queued-message cleanup runs.")

        val sentKeepDaysAck = Ack.int("CLEAN_MESSAGES_SENT_KEEP_DAYS", 60 * 24, min = 0,
            description = "Age in days after which successfully sent queued messages are deleted.")

        val failedKeepDaysAck = Ack.int("CLEAN_MESSAGES_FAILED_KEEP_DAYS", fallback = sentKeepDaysAck, min = 0,
            description = "Age in days after which failed queued messages (failed, timed out, or cancelled) are deleted. Defaults to the sent keep time.")

        private val failedStates = listOf(
            QueuedMessageState.FAILED,
            QueuedMessageState.TIMED_OUT,
            QueuedMessageState.CANCELLED,
        )
    }

    override fun execute(context: JobContext): JobResult {
        val now = System.currentTimeMillis()
        val sentCutoffAt = now - sentKeepDaysAck().days.inWholeMilliseconds
        val failedCutoffAt = now - failedKeepDaysAck().days.inWholeMilliseconds

        db {
            val sentDeleted = QueuedMessageTable.deleteWhere {
                (QueuedMessageTable.state eq QueuedMessageState.SENT) and
                        (QueuedMessageTable.lastStateChangeAt lessEq sentCutoffAt)
            }

            val failedDeleted = QueuedMessageTable.deleteWhere {
                (QueuedMessageTable.state inList failedStates) and
                        (QueuedMessageTable.lastStateChangeAt lessEq failedCutoffAt)
            }

            if (sentDeleted > 0 || failedDeleted > 0) {
                context.log.info("Deleted $sentDeleted sent and $failedDeleted failed queued messages.")
            }
        }

        return JobCompleted()
    }
}
