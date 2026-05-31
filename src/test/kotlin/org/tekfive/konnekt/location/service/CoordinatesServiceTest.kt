package org.tekfive.konnekt.location.service

import org.tekfive.keep.location.Address
import org.tekfive.keep.location.State
import org.tekfive.keep.location.Coordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoordinatesServiceTest {

    private val testAddress = Address("123 Main St", "Springfield", State.IL, "62701")

    private val stubCoordinates = Coordinates(39.7817, -89.6501)

    private fun stubProvider(
        providerName: String = "stub",
        isActive: Boolean = true,
        result: Coordinates? = stubCoordinates,
    ) = object : CoordinatesProvider {
        override val name = providerName
        override val active = isActive
        override fun geocode(address: Address) = result
    }

    @Test
    fun `initialize with explicit providers`() {
        CoordinatesService.reset()
        CoordinatesService.initialize(stubProvider())
        assertTrue(CoordinatesService.isInitialized)
        assertEquals(1, CoordinatesService.activeProviders.size)
    }

    @Test
    fun `initialize filters inactive providers`() {
        CoordinatesService.reset()
        val active = stubProvider("active", isActive = true)
        val inactive = stubProvider("inactive", isActive = false)
        CoordinatesService.initialize(active, inactive)
        assertEquals(1, CoordinatesService.activeProviders.size)
        assertEquals("active", CoordinatesService.activeProviders.first().name)
    }

    @Test
    fun `initialize fails with no active providers`() {
        CoordinatesService.reset()
        assertFailsWith<IllegalStateException> {
            CoordinatesService.initialize(stubProvider(isActive = false))
        }
    }

    @Test
    fun `initialize fails with duplicate names`() {
        CoordinatesService.reset()
        assertFailsWith<IllegalStateException> {
            CoordinatesService.initialize(stubProvider("same"), stubProvider("same"))
        }
    }

    @Test
    fun `initialize with default provider name`() {
        CoordinatesService.reset()
        val first = stubProvider("first")
        val second = stubProvider("second", result = null)
        CoordinatesService.initialize(first, second, defaultProviderName = "second")
        assertNull(CoordinatesService.geocode(testAddress))
    }

    @Test
    fun `initialize with invalid default provider name fails`() {
        CoordinatesService.reset()
        assertFailsWith<IllegalStateException> {
            CoordinatesService.initialize(stubProvider("a"), defaultProviderName = "missing")
        }
    }

    @Test
    fun `geocode delegates to default provider`() {
        CoordinatesService.reset()
        CoordinatesService.initialize(stubProvider())
        val result = CoordinatesService.geocode(testAddress)
        assertNotNull(result)
        assertEquals(stubCoordinates.latitude, result.latitude)
        assertEquals(stubCoordinates.longitude, result.longitude)
    }

    @Test
    fun `geocode fails when not initialized`() {
        CoordinatesService.reset()
        assertFailsWith<IllegalStateException> {
            CoordinatesService.geocode(testAddress)
        }
    }

    @Test
    fun `provider returns named provider`() {
        CoordinatesService.reset()
        CoordinatesService.initialize(stubProvider("a"), stubProvider("b"))
        assertEquals("b", CoordinatesService.provider("b").name)
    }

    @Test
    fun `provider fails for unknown name`() {
        CoordinatesService.reset()
        CoordinatesService.initialize(stubProvider())
        assertFailsWith<IllegalStateException> {
            CoordinatesService.provider("missing")
        }
    }

    @Test
    fun `reset clears state`() {
        CoordinatesService.reset()
        CoordinatesService.initialize(stubProvider())
        assertTrue(CoordinatesService.isInitialized)
        CoordinatesService.reset()
        assertFalse(CoordinatesService.isInitialized)
    }
}
