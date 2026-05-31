package org.tekfive.konnekt.message

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
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
        val thread = processThread
        processThread = null
        if (thread != null) {
            thread.interrupt()
            thread.join(joinTimeoutSeconds * 1000L)
        }
    }

    @Synchronized
    override fun run() {
        while (processThread == Thread.currentThread()) {
            var sleepBeforeNextPoll = true
            try {
                val now = System.currentTimeMillis()

                val defaultMinWaitRetryMSecs = defaultMinWaitRetrySecsAck() * 1000L
                val retryCutoffAt = now - defaultMinWaitRetryMSecs

                db {
                    QueuedMessageTable.select(QueuedMessageTable.id, QueuedMessageTable.state).where {

                        (QueuedMessageTable.deliverAfter.isNull() or (QueuedMessageTable.deliverAfter lessEq now)) and
                                ((QueuedMessageTable.state eq QueuedMessageState.QUEUED) or
                                        ((QueuedMessageTable.state eq QueuedMessageState.FAILED_WAITING_TO_RETRY) and (QueuedMessageTable.lastStateChangeAt lessEq retryCutoffAt))
                                )
                    }.forEach { row ->
                        sleepBeforeNextPoll = false
                        val queuedMessageId = row[QueuedMessageTable.id]
                        val state = row[QueuedMessageTable.state]

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

                    QueuedMessageTable.select(QueuedMessageTable.id, QueuedMessageTable.state).where {
                        (QueuedMessageTable.state inList listOf(QueuedMessageState.PENDING, QueuedMessageState.PROCESSING)) and
                                (QueuedMessageTable.lastStateChangeAt lessEq pendingProcessingCutoffAt)
                    }.forEach { row ->
                        val queuedMessageId = row[QueuedMessageTable.id]
                        val state = row[QueuedMessageTable.state]
                        QueuedMessageTable.update({ (QueuedMessageTable.id eq queuedMessageId) and (QueuedMessageTable.state eq state) }) { statement ->
                            sleepBeforeNextPoll = false
                            statement[QueuedMessageTable.state] = QueuedMessageState.TIMED_OUT
                            statement[QueuedMessageTable.lastStateChangeAt] = System.currentTimeMillis()
                        }
                        dbCommit()
                    }
                }
            } catch (e: Exception) {
                sleepBeforeNextPoll = true
                log.error("MessageQueueProcess exception while processing queued messages: ${e.message}", e)
            }

            if (processThread == Thread.currentThread() && sleepBeforeNextPoll) {
                try {
                    val pollMSecs = pollSleepSecsAck() * 1000L
                    Thread.sleep(pollMSecs)
                } catch (e: InterruptedException) {}
            }
        }
    }
}
