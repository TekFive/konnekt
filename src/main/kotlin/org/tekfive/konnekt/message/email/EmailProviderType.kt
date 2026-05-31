package org.tekfive.konnekt.message.email

import org.tekfive.konnekt.message.email.providers.EmailProvider
import org.tekfive.konnekt.message.email.providers.smtp.SmtpEmailProvider
import org.tekfive.konnekt.message.email.providers.twilio.TwilioEmailProvider
import org.tekfive.konnekt.message.email.providers.zeptomail.ZeptoMailEmailProvider

enum class EmailProviderType(
    val providerId: String,
    val provider: EmailProvider,
    val capabilities: Set<EmailCapability> = emptySet()
) {
    SMTP("smtp", SmtpEmailProvider),
    TWILIO_SENDGRID("twilio-sendgrid", TwilioEmailProvider, setOf(EmailCapability.STATUS_LOOKUP)),
    ZEPTO_MAIL("zepto-mail", ZeptoMailEmailProvider, setOf(EmailCapability.STATUS_LOOKUP)),
}
