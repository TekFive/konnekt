package org.tekfive.konnekt.location.service

import org.tekfive.konnekt.location.service.google.GoogleCoordinatesProvider
import org.tekfive.konnekt.location.service.mapmaker.MapMakerCoordinatesProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverProvidersTest {

    @Test
    fun `discoverProviders finds all registered CoordinatesProvider implementations`() {
        val providers = discoverProviders<CoordinatesProvider>()

        assertEquals(2, providers.size, "Expected 2 providers from META-INF/services")
        assertTrue(providers.any { it === GoogleCoordinatesProvider })
        assertTrue(providers.any { it === MapMakerCoordinatesProvider })
    }

    @Test
    fun `discoverProviders returns singleton instances`() {
        val first = discoverProviders<CoordinatesProvider>()
        val second = discoverProviders<CoordinatesProvider>()

        for (i in first.indices) {
            assertTrue(first[i] === second[i], "Provider at index $i should be the same singleton instance")
        }
    }

    @Test
    fun `discoverProviders returns empty list for unregistered type`() {
        val providers = discoverProviders<Runnable>()
        assertTrue(providers.isEmpty())
    }
}
