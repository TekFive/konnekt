package org.tekfive.konnekt.location.service.google

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GoogleCoordinatesProviderTest {

    @Test
    fun `parseResponse extracts coordinates from valid response`() {
        val json = """
        {
            "results": [
                {
                    "geometry": {
                        "location": {
                            "lat": 40.7128,
                            "lng": -74.0060
                        }
                    }
                }
            ],
            "status": "OK"
        }
        """.trimIndent()

        val result = GoogleCoordinatesProvider.parseResponse(json)
        assertNotNull(result)
        assertEquals(40.7128, result.latitude)
        assertEquals(-74.0060, result.longitude)
    }

    @Test
    fun `parseResponse returns null for ZERO_RESULTS`() {
        val json = """
        {
            "results": [],
            "status": "ZERO_RESULTS"
        }
        """.trimIndent()

        assertNull(GoogleCoordinatesProvider.parseResponse(json))
    }

    @Test
    fun `parseResponse returns null for error status`() {
        val json = """
        {
            "results": [],
            "status": "REQUEST_DENIED"
        }
        """.trimIndent()

        assertNull(GoogleCoordinatesProvider.parseResponse(json))
    }

    @Test
    fun `parseResponse returns null for missing geometry`() {
        val json = """
        {
            "results": [{}],
            "status": "OK"
        }
        """.trimIndent()

        assertNull(GoogleCoordinatesProvider.parseResponse(json))
    }

    @Test
    fun `parseResponse returns null for empty results`() {
        val json = """
        {
            "results": [],
            "status": "OK"
        }
        """.trimIndent()

        assertNull(GoogleCoordinatesProvider.parseResponse(json))
    }

    @Test
    fun `parseResponse uses first result when multiple present`() {
        val json = """
        {
            "results": [
                {
                    "geometry": {
                        "location": { "lat": 1.0, "lng": 2.0 }
                    }
                },
                {
                    "geometry": {
                        "location": { "lat": 3.0, "lng": 4.0 }
                    }
                }
            ],
            "status": "OK"
        }
        """.trimIndent()

        val result = GoogleCoordinatesProvider.parseResponse(json)
        assertNotNull(result)
        assertEquals(1.0, result.latitude)
        assertEquals(2.0, result.longitude)
    }

    @Test
    fun `provider name is google`() {
        assertEquals("google", GoogleCoordinatesProvider.name)
    }
}
