package org.tekfive.konnekt.message

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tekfive.ack.Ack
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.db.db
import org.tekfive.keep.db.dbCommit
import org.tekfive.keep.job.db.JobRecordsTable

object MessageQueueProcessor : Runnable {

    @Volatile
    private var processThread: Thread? = null

    val pollSleepSecsAck = Ack.int("POLL_SLEEP_SECONDS", 20, namespace = "MQP", description = "Seconds the message queue processor sleeps between polls.")

    val defaultMinWaitRetrySecsAck = Ack.int("DEFAULT_MIN_WAIT_RETRY_SECS", 120, namespace = "MQP", description = "Default minimum seconds before a failed queued message is retried.")

    val maxPendingProcessingMinutesAck = Ack.int("MAX_PENDING_MINUTES", 30, namespace = "MQP", description = "Minutes a message may stay in processing before being considered stalled.")

    private val log: Logger = LoggerFactory.getLogger(MessageQueueProcessor::class.java)

    private val startKey = "StartKey"

    fun start() {
        synchronized(startKey) {
            if (processThread?.isAlive != true) {
                processThread = Thread(this, "MessageQueueProcessor").also { it.start() }
            }
        }
    }

    fun stop(joinTimeoutSeconds: Int = 15) {
        val thread: Thread?
        synchronized(startKey) {
            thread = processThread
            processThread = null
        }
        if (thread != null) {
            thread.interrupt()
            thread.join(joinTimeoutSeconds * 1000L)
        }
    }

    override fun run() {
        while (processThread == Thread.currentThread()) {
            var sleepBeforeNextPoll = true
            try {
                val now = System.currentTimeMillis()

                val defaultMinWaitRetryMSecs = defaultMinWaitRetrySecsAck() * 1000L
                val retryCutoffAt = now - defaultMinWaitRetryMSecs

                db {
                    val readyMessages = QueuedMessageTable.select(QueuedMessageTable.id, QueuedMessageTable.state).where {

                        (QueuedMessageTable.deliverAfter.isNull() or (QueuedMessageTable.deliverAfter lessEq now)) and
                                ((QueuedMessageTable.state eq QueuedMessageState.QUEUED) or
                                        ((QueuedMessageTable.state eq QueuedMessageState.FAILED_WAITING_TO_RETRY) and (QueuedMessageTable.lastStateChangeAt lessEq retryCutoffAt))
                                )
                    }.map { row -> row[QueuedMessageTable.id] to row[QueuedMessageTable.state] }

                    for ((queuedMessageId, state) in readyMessages) {
                        sleepBeforeNextPoll = false

                        val updated = QueuedMessageTable.update({ (QueuedMessageTable.id eq queuedMessageId) and (QueuedMessageTable.state eq state) }) { statement ->
                            statement[QueuedMessageTable.state] = QueuedMessageState.PENDING
                            statement[QueuedMessageTable.lastStateChangeAt] = System.currentTimeMillis()
                        }
                        if (updated == 1) {
                            JobRecordsTable.insertJob(SendMessageJob, details = JsonObject(mapOf(SendMessageJob.QUEUED_MESSAGE_ID_PROPERTY to queuedMessageId)))
                            dbCommit()
                        }
                    }

                    val maxPendingProcessingMSecs = maxPendingProcessingMinutesAck() * 60_000L
                    val pendingProcessingCutoffAt = now - maxPendingProcessingMSecs

                    val stalledMessages = QueuedMessageTable.select(QueuedMessageTable.id, QueuedMessageTable.state).where {
                        (QueuedMessageTable.state inList listOf(QueuedMessageState.PENDING, QueuedMessageState.PROCESSING)) and
                                (QueuedMessageTable.lastStateChangeAt lessEq pendingProcessingCutoffAt)
                    }.map { row -> row[QueuedMessageTable.id] to row[QueuedMessageTable.state] }

                    for ((queuedMessageId, state) in stalledMessages) {
                        sleepBeforeNextPoll = false

                        // A stalled PENDING message never started sending (its job never claimed it),
                        // so re-queueing cannot double-send. A stalled PROCESSING message has an
                        // unknown send outcome, so it is conservatively timed out.
                        val newState = if (state == QueuedMessageState.PENDING) QueuedMessageState.QUEUED else QueuedMessageState.TIMED_OUT

                        val updated = QueuedMessageTable.update({ (QueuedMessageTable.id eq queuedMessageId) and (QueuedMessageTable.state eq state) }) { statement ->
                            statement[QueuedMessageTable.state] = newState
                            statement[QueuedMessageTable.lastStateChangeAt] = System.currentTimeMillis()
                        }
                        if (updated == 1) {
                            if (newState == QueuedMessageState.TIMED_OUT) {
                                log.warn("Queued message {} stalled in PROCESSING and was timed out.", queuedMessageId)
                            } else {
                                log.warn("Queued message {} stalled in PENDING and was re-queued.", queuedMessageId)
                            }
                            dbCommit()
                        }
                    }
                }
            } catch (e: Exception) {
                sleepBeforeNextPoll = true
                log.error("MessageQueueProcessor exception while processing queued messages.", e)
            }

            if (processThread == Thread.currentThread() && sleepBeforeNextPoll) {
                try {
                    val pollMSecs = pollSleepSecsAck() * 1000L
                    Thread.sleep(pollMSecs)
                } catch (e: InterruptedException) {
                    // Interrupt is the stop signal; the loop condition decides whether to exit.
                    log.debug("MessageQueueProcessor poll sleep interrupted.")
                }
            }
        }
    }
}
