package org.tekfive.konnekt.storage

import org.tekfive.konnekt.storage.providers.FileSystemKrateProvider
import org.tekfive.konnekt.storage.providers.MemoryKrateProvider
import org.tekfive.konnekt.storage.providers.s3.S3KrateProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverProvidersTest {

    @Test
    fun `discoverProviders finds all registered KrateProvider implementations`() {
        val providers = discoverProviders<KrateProvider>()

        assertEquals(3, providers.size, "Expected 3 providers from META-INF/services")
        assertTrue(providers.any { it === MemoryKrateProvider })
        assertTrue(providers.any { it === FileSystemKrateProvider })
        assertTrue(providers.any { it === S3KrateProvider })
    }

    @Test
    fun `discoverProviders returns singleton instances`() {
        val first = discoverProviders<KrateProvider>()
        val second = discoverProviders<KrateProvider>()

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
