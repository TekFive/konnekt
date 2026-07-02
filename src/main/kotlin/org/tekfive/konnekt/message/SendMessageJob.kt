package org.tekfive.konnekt.message

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.tekfive.keep.db.db
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobFailed
import org.tekfive.keep.job.JobResult
import org.tekfive.keep.job.JobSpec
import org.tekfive.konnekt.message.email.EmailService
import org.tekfive.konnekt.message.sms.SmsService
import org.tekfive.konnekt.message.team.TeamMessageService

class SendMessageJob : Job {

    companion object : JobSpec {

        const val QUEUED_MESSAGE_ID_PROPERTY = "queuedMessageId"

        internal fun buildMessageReceipts(
            queuedMessageId: Long,
            queuedMessage: QueuedMessage,
            receiptDetails: MessageReceiptDetails,
        ): List<MessageReceipt> {
            queuedMessage.receiptDetails = receiptDetails.providerTrackingData
            val receiptDetailsString = receiptDetails.providerTrackingData.toJsonString()

            return receiptDetails.recipientAddresses.map { recipientAddress ->
                MessageReceipt(
                    queuedMessageId = queuedMessageId,
                    providerId = receiptDetails.endpointId,
                    recipientAddress = recipientAddress,
                    status = MessageReceiptStatus.WAITING,
                    details = receiptDetailsString,
                )
            }
        }

        override fun createJob(): Job {
            return SendMessageJob()
        }

    }

    override fun execute(context: JobContext): JobResult {
        val queuedMessageId = context.details?.get(QUEUED_MESSAGE_ID_PROPERTY)?.long
        if (queuedMessageId == null) {
            return JobFailed("No $QUEUED_MESSAGE_ID_PROPERTY property provided in job details.")
        }

        val queuedMessage = QueuedMessageTable.findById(queuedMessageId)
        if (queuedMessage == null) {
            return JobFailed("Unable to find queued message with id $queuedMessageId")
        }

        if (queuedMessage.state != QueuedMessageState.PENDING) {
            return JobFailed("Queued message $queuedMessageId is not in PENDING state (state=${queuedMessage.state}).")
        }

        val attempt = db {
            val now = System.currentTimeMillis()
            val updateCount = QueuedMessageTable.update({(QueuedMessageTable.id eq queuedMessageId) and (QueuedMessageTable.state eq QueuedMessageState.PENDING) }) { statement ->
                statement[QueuedMessageTable.state] = QueuedMessageState.PROCESSING
                statement[QueuedMessageTable.attemptCount] = queuedMessage.attemptCount + 1
                statement[QueuedMessageTable.lastStateChangeAt] = now
            }

            if (updateCount != 1) {
                throw JobFailed("Unable to grab queued message send lock.")
            }

            queuedMessage.state = QueuedMessageState.PROCESSING
            queuedMessage.attemptCount += 1
            queuedMessage.lastStateChangeAt = now
            SendMessageAttemptTable.create(SendMessageAttempt(queuedMessageId, SendMessageAttemptState.SENDING))
        }

        var result: JobResult = JobCompleted()

        try {
            val messageReceiptDetails = when (queuedMessage.type) {
                MessageType.EMAIL -> {
                    EmailService.send(queuedMessage)
                }

                MessageType.SMS -> {
                    SmsService.send(queuedMessage)
                }

                MessageType.TEAM_MESSAGE -> {
                    TeamMessageService.send(queuedMessage)
                }
            }

            queuedMessage.state = QueuedMessageState.SENT
            attempt.state = SendMessageAttemptState.SENT

            if (messageReceiptDetails != null) {
                try {
                    val messageReceipts = buildMessageReceipts(queuedMessageId, queuedMessage, messageReceiptDetails)
                    for (messageReceipt in messageReceipts) {
                        MessageReceiptTable.create(messageReceipt)
                    }
                } catch (e: Exception) {
                    // The message was delivered; a receipt-persistence failure must not flip it to FAILED.
                    context.log.error("Failed to persist message receipts for message ${queuedMessage.id}", e)
                    result = JobFailed("Failed to persist message receipts for message ${queuedMessage.id}")
                }
            }

        } catch (e: Exception) {
            attempt.state = SendMessageAttemptState.FAILED
            attempt.details = e.message
            val recoverable = e is MessagingException && e.recoverable
            if (recoverable) {
                context.log.error("Failed to send message ${queuedMessage.id} from a recoverable error.", e)
                if (queuedMessage.attemptCount < queuedMessage.maxAttempts) {
                    queuedMessage.state = QueuedMessageState.FAILED_WAITING_TO_RETRY
                } else {
                    queuedMessage.state = QueuedMessageState.FAILED
                }
            } else {
                context.log.error("Failed to send message ${queuedMessage.id}", e)
                queuedMessage.state = QueuedMessageState.FAILED
            }
            result = JobFailed(e.message ?: "Failed to send queued message.")
        } finally {
            // Guard on PROCESSING so a concurrent external transition (e.g. the queue processor
            // marking a stalled message TIMED_OUT) is not clobbered or resurrected.
            val updated = db {
                QueuedMessageTable.update({ (QueuedMessageTable.id eq queuedMessageId) and (QueuedMessageTable.state eq QueuedMessageState.PROCESSING) }) { statement ->
                    statement[QueuedMessageTable.state] = queuedMessage.state
                    statement[QueuedMessageTable.lastStateChangeAt] = queuedMessage.lastStateChangeAt
                    statement[QueuedMessageTable.receiptDetails] = queuedMessage.receiptDetails
                }
            }
            if (updated != 1) {
                context.log.error("Queued message ${queuedMessage.id} was externally transitioned while sending; final state ${queuedMessage.state} was not recorded.")
            }
            attempt.endedAt = System.currentTimeMillis()
            SendMessageAttemptTable.update(attempt)
        }

        return result
    }
}
