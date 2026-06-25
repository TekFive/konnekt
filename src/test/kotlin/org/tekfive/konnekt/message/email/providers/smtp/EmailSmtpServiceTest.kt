package org.tekfive.konnekt.message.email.providers.smtp

import jakarta.mail.Session
import jakarta.mail.internet.MimeMultipart
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.JsonMappingException
import org.tekfive.jfk.json
import org.tekfive.konnekt.message.MessageAddress
import org.tekfive.konnekt.message.MessageRecipient
import org.tekfive.konnekt.message.email.EmailAttachment
import org.tekfive.konnekt.message.email.EmailMessage
import org.tekfive.konnekt.message.email.EmailStatus
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EmailSmtpServiceTest {

    @Test
    fun `valid config extracts host and port`() {
        val config = smtpConfiguration(host = "smtp.example.com", port = 465)

        val props = SmtpEmailProvider.buildSessionProperties(config)

        assertEquals("smtp.example.com", props["mail.smtp.host"])
        assertEquals("465", props["mail.smtp.port"])
    }

    @Test
    fun `default port 587 when not specified`() {
        val config = smtpConfiguration(host = "smtp.example.com")

        val props = SmtpEmailProvider.buildSessionProperties(config)

        assertEquals("587", props["mail.smtp.port"])
    }

    @Test
    fun `missing host throws`() {
        val config = JsonObject()

        assertFailsWith<JsonMappingException> {
            SmtpConfiguration.fromJson(config)
        }
    }

    @Test
    fun `TLS defaults startTls true and sslEnabled false`() {
        val config = smtpConfiguration(host = "smtp.example.com")

        val props = SmtpEmailProvider.buildSessionProperties(config)

        assertEquals("true", props["mail.smtp.starttls.enable"])
        assertEquals("false", props["mail.smtp.ssl.enable"])
    }

    @Test
    fun `auth and TLS overrides`() {
        val config = smtpConfiguration(
            host = "smtp.example.com",
            authenticate = true,
            username = "user",
            password = "pass",
            startTls = false,
            sslEnabled = true,
        )

        val props = SmtpEmailProvider.buildSessionProperties(config)

        assertEquals("true", props["mail.smtp.auth"])
        assertEquals("false", props["mail.smtp.starttls.enable"])
        assertEquals("true", props["mail.smtp.ssl.enable"])
    }

    @Test
    fun `timeout overrides`() {
        val config = smtpConfiguration(
            host = "smtp.example.com",
            connectionTimeoutMSecs = 5000,
            timeoutMSecs = 3000,
            writeTimeoutMSecs = 7000,
        )

        val props = SmtpEmailProvider.buildSessionProperties(config)

        assertEquals("5000", props["mail.smtp.connectiontimeout"])
        assertEquals("3000", props["mail.smtp.timeout"])
        assertEquals("7000", props["mail.smtp.writetimeout"])
    }

    @Test
    fun `default timeouts are 10000`() {
        val config = smtpConfiguration(host = "smtp.example.com")

        val props = SmtpEmailProvider.buildSessionProperties(config)

        assertEquals("10000", props["mail.smtp.connectiontimeout"])
        assertEquals("10000", props["mail.smtp.timeout"])
        assertEquals("10000", props["mail.smtp.writetimeout"])
    }

    @Test
    fun `no credentials returns null authenticator`() {
        val config = smtpConfiguration(host = "smtp.example.com")

        val authenticator = SmtpEmailProvider.buildAuthenticator(config)

        assertNull(authenticator)
    }

    @Test
    fun `credentials present returns authenticator`() {
        val config = smtpConfiguration(
            host = "smtp.example.com",
            authenticate = true,
            username = "user@example.com",
            password = "secret",
        )

        val authenticator = SmtpEmailProvider.buildAuthenticator(config)

        assertNotNull(authenticator)
    }

    @Test
    fun `auth enabled without credentials returns null authenticator`() {
        val config = smtpConfiguration(
            host = "smtp.example.com",
            authenticate = true,
        )

        assertNull(SmtpEmailProvider.buildAuthenticator(config))
    }

    @Test
    fun `send returns typed response`() {
        val smtpServer = FakeSmtpServer.start()
        try {
            val config = json {
                "host" set "127.0.0.1"
                "port" set smtpServer.port
                "startTls" set false
                "sslEnabled" set false
                "authenticate" set false
            }

            val response = SmtpEmailProvider.send(
                EmailMessage(
                    to = listOf(MessageRecipient("to@example.com", "To")),
                    from = MessageAddress("sender@example.com", "Sender"),
                    subject = "Subject",
                    body = "Body",
                    contentType = EmailMessage.TEXT_CONTENT_TYPE,
                ),
                config,
            )

            assertEquals("", response.messageId)
            assertEquals("smtp", response.providerId)
            assertEquals(EmailStatus.SENT, response.status)
        } finally {
            smtpServer.close()
        }
    }

    @Test
    fun `buildMimeMessage without attachments is a single body part`() {
        val session = Session.getInstance(Properties())
        val message = EmailMessage(
            to = listOf(MessageRecipient("to@example.com", "To")),
            from = MessageAddress("sender@example.com", "Sender"),
            subject = "Subject",
            body = "Body",
            contentType = EmailMessage.TEXT_CONTENT_TYPE,
        )

        val mime = SmtpEmailProvider.buildMimeMessage(message, session)

        assertEquals("Body", mime.content)
    }

    @Test
    fun `buildMimeMessage with attachment is multipart with body and attachment parts`() {
        val session = Session.getInstance(Properties())
        val message = EmailMessage(
            to = listOf(MessageRecipient("to@example.com", "To")),
            from = MessageAddress("sender@example.com", "Sender"),
            subject = "Subject",
            body = "Body",
            contentType = EmailMessage.TEXT_CONTENT_TYPE,
            attachments = listOf(
                EmailAttachment(
                    fileName = "report.pdf",
                    contentType = "application/pdf",
                    content = "PDFDATA".toByteArray(Charsets.UTF_8),
                ),
            ),
        )

        val mime = SmtpEmailProvider.buildMimeMessage(message, session)

        val multipart = mime.content as MimeMultipart
        assertEquals(2, multipart.count)
        val attachmentPart = multipart.getBodyPart(1)
        assertEquals("report.pdf", attachmentPart.fileName)
        assertEquals("attachment", attachmentPart.disposition)
    }
}

private fun smtpConfiguration(
    host: String,
    port: Int? = null,
    startTls: Boolean? = null,
    sslEnabled: Boolean? = null,
    connectionTimeoutMSecs: Int? = null,
    timeoutMSecs: Int? = null,
    writeTimeoutMSecs: Int? = null,
    authenticate: Boolean = false,
    username: String? = null,
    password: String? = null,
): SmtpConfiguration {
    return SmtpConfiguration(
        host = host,
        port = port,
        startTls = startTls,
        sslEnabled = sslEnabled,
        connectionTimeoutMSecs = connectionTimeoutMSecs,
        timeoutMSecs = timeoutMSecs,
        writeTimeoutMSecs = writeTimeoutMSecs,
        authenticate = authenticate,
        username = username,
        password = password,
    )
}

private class FakeSmtpServer private constructor(
    private val serverSocket: ServerSocket,
) : AutoCloseable {

    val port: Int = serverSocket.localPort
    @Volatile
    private var acceptedSocket: Socket? = null

    init {
        thread(name = "fake-smtp-server", isDaemon = true) {
            serverSocket.use { socketServer ->
                socketServer.accept().also { socket ->
                    acceptedSocket = socket
                }.use { socket ->
                    handle(socket)
                }
            }
        }
    }

    override fun close() {
        acceptedSocket?.close()
        serverSocket.close()
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
