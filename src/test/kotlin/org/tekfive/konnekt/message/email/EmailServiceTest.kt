package org.tekfive.konnekt.message.email

import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.MessagingException
import org.tekfive.konnekt.message.QueuedMessage
import org.tekfive.konnekt.message.QueuedMessageMetadata
import java.net.InetSocketAddress
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.sun.net.httpserver.HttpServer

class EmailServiceTest {

    @AfterTest
    fun tearDown() {
        EmailService.reset()
    }

    @Test
    fun `smtp email service returns typed response and no status lookup`() {
        val smtpServer = FakeSmtpServer.start()
        try {
            val endpoint = buildSmtpEndpoint(smtpServer.port)

            val response = EmailService.send(
                EmailMessage(
                    to = listOf(MessageRecipient("to@example.com", "To")),
                    from = MessageAddress("sender@example.com", "Sender"),
                    subject = "Subject",
                    body = "Body",
                    contentType = EmailMessage.TEXT_CONTENT_TYPE,
                ),
                endpoint,
            )

            assertEquals("", response.messageId)
            assertEquals("smtp", response.providerId)
            assertEquals(EmailStatus.SENT, response.status)
            assertNull(EmailService.status("message-1", endpoint))
        } finally {
            smtpServer.close()
        }
    }

    @Test
    fun `queued smtp email with receipt tracking does not produce receipt details`() {
        val smtpServer = FakeSmtpServer.start()
        try {
            val endpoint = buildSmtpEndpoint(smtpServer.port)
            EmailService.registerResolver { endpoint }

            val queuedMessage = queuedEmailMessage(
                label = "label",
                endpoint = endpoint,
                trackReceipt = true,
            )

            val receiptDetails = EmailService.send(queuedMessage)

            assertNull(receiptDetails)
        } finally {
            EmailService.reset()
            smtpServer.close()
        }
    }

    @Test
    fun `queued sendgrid email without tracking id does not produce receipt details`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/v3/mail/send") { exchange ->
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
            server.start()

            val endpoint = buildSendGridEndpoint(server.address.port)
            EmailService.registerResolver { endpoint }

            val queuedMessage = queuedEmailMessage(
                label = "label",
                endpoint = endpoint,
                trackReceipt = true,
            )

            val receiptDetails = EmailService.send(queuedMessage)

            assertNull(receiptDetails)
        } finally {
            EmailService.reset()
            server.stop(0)
        }
    }

    @Test
    fun `queued sendgrid email with tracking id returns receipt details`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/v3/mail/send") { exchange ->
                exchange.responseHeaders.add("X-Message-Id", "msg-123")
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
            server.start()

            val endpoint = buildSendGridEndpoint(server.address.port)
            EmailService.registerResolver { endpoint }

            val queuedMessage = queuedEmailMessage(
                label = "label",
                endpoint = endpoint,
                trackReceipt = true,
            )

            val receiptDetails = EmailService.send(queuedMessage)

            assertNotNull(receiptDetails)
            assertEquals(endpoint.id, receiptDetails.endpointId)
            assertEquals(listOf("to@example.com"), receiptDetails.recipientAddresses)
            assertEquals(endpoint.id, receiptDetails.providerTrackingData.string("endpointId"))
            assertEquals("msg-123", receiptDetails.providerTrackingData.string("messageId"))
        } finally {
            EmailService.reset()
            server.stop(0)
        }
    }

    @Test
    fun `sendgrid server error surfaces as recoverable messaging exception without response content`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            val responseBody = """{"errors":[{"message":"rejected recipient to@example.com"}]}"""
            server.createContext("/v3/mail/send") { exchange ->
                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(500, bytes.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(bytes)
                }
                exchange.close()
            }
            server.start()

            val exception = assertFailsWith<MessagingException> {
                EmailService.send(plainEmailMessage(), buildSendGridEndpoint(server.address.port))
            }

            assertTrue(exception.recoverable)
            assertFalse(exception.message.orEmpty().contains("to@example.com"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sendgrid client error surfaces as non-recoverable messaging exception`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/v3/mail/send") { exchange ->
                exchange.sendResponseHeaders(400, -1)
                exchange.close()
            }
            server.start()

            val exception = assertFailsWith<MessagingException> {
                EmailService.send(plainEmailMessage(), buildSendGridEndpoint(server.address.port))
            }

            assertFalse(exception.recoverable)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `invalid smtp recipient surfaces as non-recoverable messaging exception without the address`() {
        val endpoint = buildSmtpEndpoint(port = 1)
        val injectedAddress = "victim@example.com\r\nBcc: attacker@evil.com"
        val message = EmailMessage(
            to = listOf(MessageRecipient(injectedAddress)),
            from = MessageAddress("sender@example.com", "Sender"),
            subject = "Subject",
            body = "Body",
            contentType = EmailMessage.TEXT_CONTENT_TYPE,
        )

        val exception = assertFailsWith<MessagingException> {
            EmailService.send(message, endpoint)
        }

        assertFalse(exception.recoverable)
        assertFalse(exception.message.orEmpty().contains("attacker@evil.com"))
        assertFalse(exception.message.orEmpty().contains("victim@example.com"))
    }

    @Test
    fun `attachments over the configuration limit are rejected before dispatch`() {
        val endpoint = buildSmtpEndpoint(port = 1, maxAttachmentsSizeBytes = 10)
        val message = emailMessageWithAttachment("report.pdf", ByteArray(32))

        val exception = assertFailsWith<MessagingException> {
            EmailService.send(message, endpoint)
        }

        assertFalse(exception.recoverable)
        assertTrue(exception.message.orEmpty().contains("32"))
        assertTrue(exception.message.orEmpty().contains("10"))
        assertFalse(exception.message.orEmpty().contains("report.pdf"))
    }

    @Test
    fun `attachments within the configuration limit are sent`() {
        val smtpServer = FakeSmtpServer.start()
        try {
            val endpoint = buildSmtpEndpoint(smtpServer.port, maxAttachmentsSizeBytes = 1024)

            val response = EmailService.send(emailMessageWithAttachment("report.pdf", ByteArray(32)), endpoint)

            assertEquals(EmailStatus.SENT, response.status)
        } finally {
            smtpServer.close()
        }
    }

    @Test
    fun `attachments fall back to the global default limit when the configuration has none`() {
        val endpoint = buildSmtpEndpoint(port = 1)
        val overDefaultLimit = ByteArray(EmailService.maxAttachmentsSizeBytesAck().toInt() + 1)

        val exception = assertFailsWith<MessagingException> {
            EmailService.send(emailMessageWithAttachment("report.pdf", overDefaultLimit), endpoint)
        }

        assertFalse(exception.recoverable)
        assertFalse(exception.message.orEmpty().contains("report.pdf"))
    }
}

private fun emailMessageWithAttachment(fileName: String, content: ByteArray): EmailMessage {
    return EmailMessage(
        to = listOf(MessageRecipient("to@example.com", "To")),
        from = MessageAddress("sender@example.com", "Sender"),
        subject = "Subject",
        body = "Body",
        contentType = EmailMessage.TEXT_CONTENT_TYPE,
        attachments = listOf(
            EmailAttachment(
                fileName = fileName,
                contentType = "application/pdf",
                content = content,
            ),
        ),
    )
}

private fun plainEmailMessage(): EmailMessage {
    return EmailMessage(
        to = listOf(MessageRecipient("to@example.com", "To")),
        from = MessageAddress("sender@example.com", "Sender"),
        subject = "Subject",
        body = "Body",
        contentType = EmailMessage.TEXT_CONTENT_TYPE,
    )
}

private fun buildSmtpEndpoint(port: Int, maxAttachmentsSizeBytes: Long? = null): EmailProviderTypeConfiguration {
    return EmailProviderTypeConfiguration(
        id = "smtp-endpoint",
        type = EmailProviderType.SMTP,
        configuration = json {
            "host" set "127.0.0.1"
            "port" set port
            "startTls" set false
            "sslEnabled" set false
            "authenticate" set false
        },
        maxAttachmentsSizeBytes = maxAttachmentsSizeBytes,
    )
}

private fun buildSendGridEndpoint(port: Int): EmailProviderTypeConfiguration {
    return EmailProviderTypeConfiguration(
        id = "sendgrid-endpoint",
        type = EmailProviderType.TWILIO_SENDGRID,
        configuration = json {
            "apiKey" set "SG.test"
            "baseUrl" set "http://127.0.0.1:$port"
        },
    )
}

private fun queuedEmailMessage(
    label: String,
    endpoint: EmailProviderTypeConfiguration,
    trackReceipt: Boolean,
): QueuedMessage {
    return QueuedMessage(
        metadata = QueuedMessageMetadata(
            label = label,
            description = null,
            trackReceipt = trackReceipt,
        ),
        message = EmailMessage(
            to = listOf(MessageRecipient("to@example.com", "To")),
            from = MessageAddress("sender@example.com", "Sender"),
            subject = "Subject",
            body = "Body",
            contentType = EmailMessage.TEXT_CONTENT_TYPE,
        ),
        providerTypeConfiguration = endpoint,
    )
}

private class FakeSmtpServer private constructor(
    private val serverSocket: ServerSocket,
) : AutoCloseable {

    val port: Int = serverSocket.localPort
    private val started = CountDownLatch(1)
    private val completed = CountDownLatch(1)
    @Volatile
    private var acceptedSocket: Socket? = null

    init {
        thread(name = "fake-smtp-server", isDaemon = true) {
            try {
                started.countDown()
                serverSocket.use { socketServer ->
                    socketServer.accept().also { socket ->
                        acceptedSocket = socket
                    }.use { socket ->
                        handle(socket)
                    }
                }
            } finally {
                completed.countDown()
            }
        }
        started.await(5, TimeUnit.SECONDS)
    }

    override fun close() {
        acceptedSocket?.close()
        serverSocket.close()
        completed.await(5, TimeUnit.SECONDS)
    }

    companion object {
        fun start(): FakeSmtpServer {
            return FakeSmtpServer(ServerSocket(0))
        }
    }

    private fun handle(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

        writer.write("220 localhost ready\r\n")
        writer.flush()

        var inData = false
        while (true) {
            val line = reader.readLine() ?: break

            if (inData) {
                if (line == ".") {
                    writer.write("250 queued\r\n")
                    writer.flush()
                    inData = false
                }
                continue
            }

            when {
                line.startsWith("EHLO") || line.startsWith("HELO") -> {
                    writer.write("250 localhost\r\n")
                    writer.flush()
                }

                line.startsWith("MAIL FROM") -> {
                    writer.write("250 sender ok\r\n")
                    writer.flush()
                }

                line.startsWith("RCPT TO") -> {
                    writer.write("250 recipient ok\r\n")
                    writer.flush()
                }

                line == "DATA" -> {
                    writer.write("354 end data with <CR><LF>.<CR><LF>\r\n")
                    writer.flush()
                    inData = true
                }

                line == "QUIT" -> {
                    writer.write("221 bye\r\n")
                    writer.flush()
                    break
                }

                else -> {
                    writer.write("250 ok\r\n")
                    writer.flush()
                }
            }
        }
    }
}
