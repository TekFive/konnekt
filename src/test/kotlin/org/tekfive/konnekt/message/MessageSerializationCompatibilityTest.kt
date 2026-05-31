package org.tekfive.konnekt.message

import org.tekfive.konnekt.message.email.EmailMessage
import org.tekfive.konnekt.message.email.EmailProviderType
import org.tekfive.konnekt.message.email.EmailProviderTypeConfiguration
import org.tekfive.konnekt.message.sms.QueuedSmsMessage
import org.tekfive.konnekt.message.sms.SmsMessage
import org.tekfive.konnekt.message.team.QueuedTeamMessage
import org.tekfive.konnekt.message.team.TeamMessage
import org.tekfive.konnekt.message.team.TeamMessagePriority
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageSerializationCompatibilityTest {

    @Test
    fun `queued mixed message types preserve their serialized recipient models`() {
        val email = EmailMessage(
            to = listOf(MessageRecipient("to@example.com", "To")),
            cc = listOf(MessageRecipient("cc@example.com", "CC")),
            from = MessageAddress("sender@example.com", "Sender"),
            subject = "Email Subject",
            body = "Email Body",
            contentType = EmailMessage.TEXT_CONTENT_TYPE,
        )
        val secure = QueuedTeamMessage(
            label = "secure",
            endpointId = "tc-endpoint",
            to = listOf(MessageRecipient("role:on-call", "On Call")),
            from = MessageAddress("system", "System"),
            subject = "Secure Subject",
            body = "Secure Body",
            priority = TeamMessagePriority.HIGH,
        )
        val sms = QueuedSmsMessage(
            label = "sms",
            endpointId = "sms-endpoint",
            to = listOf(MessageAddress("+15555550100", "Primary")),
            from = MessageAddress("+15555550999", "System"),
            body = "SMS Body",
        )

        val emailQueued = QueuedMessage(
            metadata = QueuedMessageMetadata(
                label = "email",
                description = null,
                trackReceipt = false,
            ),
            message = email,
            providerTypeConfiguration = EmailProviderTypeConfiguration(
                id = "test-endpoint",
                type = EmailProviderType.SMTP,
                configuration = org.tekfive.jfk.json {
                    "host" set "smtp.example.com"
                },
            ),
        )
        val secureQueued = secure.toQueuedMessage()
        val smsQueued = sms.toQueuedMessage()
        val restoredEmail = EmailMessage.fromJson(emailQueued.message)
        val restoredSecure = TeamMessage.fromJson(secureQueued.message)
        val restoredSms = SmsMessage.fromJson(smsQueued.message)

        assertEquals(MessageType.EMAIL, emailQueued.type)
        assertEquals(MessageType.TEAM_MESSAGE, secureQueued.type)
        assertEquals(MessageType.SMS, smsQueued.type)
        assertEquals(listOf("to@example.com"), emailQueued.recipients)
        assertEquals(listOf("role:on-call"), secureQueued.recipients)
        assertEquals(listOf("+15555550100"), smsQueued.recipients)
        assertEquals(email.to, restoredEmail.to)
        assertEquals(email.cc, restoredEmail.cc)
        assertEquals(secure.to, restoredSecure.to)
        assertEquals(TeamMessagePriority.HIGH, restoredSecure.priority)
        assertEquals("tc-endpoint", secureQueued.providerTypeConfigurationId)
        assertEquals(sms.to, restoredSms.to)
        assertEquals(sms.from, restoredSms.from)
        assertEquals(sms.body, restoredSms.body)
    }
}
