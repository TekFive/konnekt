package org.tekfive.konnekt.location.service.google

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GooglePlacesServiceTest {
    @Test
    fun `isActive returns false when API key is not configured`() {
        assertFalse(GooglePlacesService.isActive)
    }

    @Test
    fun `apiKey returns null when not configured`() {
        assertEquals(null, GooglePlacesService.apiKey)
    }
}
