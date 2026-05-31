package org.tekfive.konnekt.location.service.mapmaker

import org.tekfive.jfk.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tekfive.ack.Ack
import org.tekfive.keep.location.Address
import org.tekfive.keep.location.Coordinates
import org.tekfive.konnekt.location.service.CoordinatesProvider

/**
 * A [CoordinatesProvider] backed by the MapMaker (geocode.maps.co) Geocoding API.
 *
 * Active when the `KONNEKT_MAP_MAKER_GEOCODING_API_KEY` property is defined.
 */
object MapMakerCoordinatesProvider : CoordinatesProvider {

    val apiKeyProp = Ack.secret("KONNEKT_MAP_MAKER_GEOCODING_API_KEY", description = "API key for the Map Maker geocoding provider.")

    val apiRootUrlProp = Ack.string("KONNEKT_MAP_MAKER_GEOCODING_API_ROOT_URL", "https://geocode.maps.co", description = "Base URL for the Map Maker geocoding API.")

    override val name: String = "mapmaker"

    override val active: Boolean
        get() = apiKeyProp.isDefined

    private val client by lazy { OkHttpClient() }

    override fun geocode(address: Address): Coordinates? {
        val searchParameters = listOf("street" to address.street, "city" to address.city, "state" to address.state?.displayName, "postalCode" to address.zip)
            .filter { !it.second.isNullOrBlank() }.joinToString("&") {"${it.first}=${it.second}"}

        val apiKey = apiKeyProp()!!
        val url = "${apiRootUrlProp()}/search?api_key=$apiKey&$searchParameters"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 429) {
                return null // Rate limited
            }
            if (!response.isSuccessful) {
                return null
            }

            val body = response.body?.string() ?: return null
            return parseResponse(body)
        }
    }

    internal fun parseResponse(json: String): Coordinates? {
        val root = Json.parse(json)
        val jsonArray = root.array ?: return null

        for (element in jsonArray) {
            val obj = element.obj ?: continue
            val lat = obj.string("lat")?.toDoubleOrNull()
            val lon = obj.string("lon")?.toDoubleOrNull()

            if (lat != null && lon != null) {
                return Coordinates(lat, lon)
            }
        }

        return null
    }
}
