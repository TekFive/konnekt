package org.tekfive.konnekt.message.sms

import kotlin.test.Test
import kotlin.test.assertEquals

class SmsResponseTest {

    @Test
    fun `sms response contract uses unknown as the default status`() {
        val response = SmsResponse(
            messageId = "message-1",
            providerId = "provider-1",
        )

        assertEquals(SmsStatus.UNKNOWN, response.status)
        assertEquals("Status Lookup", SmsCapability.STATUS_LOOKUP.displayName)
        assertEquals(SmsStatus.UNKNOWN, SmsStatus.valueOf("UNKNOWN"))
    }
}
