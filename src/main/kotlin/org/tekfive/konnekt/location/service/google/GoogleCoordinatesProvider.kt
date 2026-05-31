package org.tekfive.konnekt.location.service.google

import org.tekfive.jfk.asRequiredJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tekfive.ack.Ack
import org.tekfive.keep.location.Address
import org.tekfive.keep.location.Coordinates
import org.tekfive.konnekt.location.service.CoordinatesProvider
import java.net.URLEncoder

/**
 * A [CoordinatesProvider] backed by the Google Geocoding API.
 *
 * Active when the `KONNEKT_GOOGLE_GEOCODING_API_KEY` property is defined.
 */
object GoogleCoordinatesProvider : CoordinatesProvider {

    val apiKeyProp = Ack.secret("KONNEKT_GOOGLE_GEOCODING_API_KEY", description = "API key for the Google geocoding provider.")

    override val name: String = "google"

    override val active: Boolean
        get() = apiKeyProp.isDefined

    private val client by lazy { OkHttpClient() }

    override fun geocode(address: Address): Coordinates? {
        val addressString = listOf(address.street, address.city, address.state?.displayName, address.zip).filter { !it.isNullOrBlank() }.joinToString(", ")
        val encodedAddress = URLEncoder.encode(addressString, "UTF-8")
        val apiKey = apiKeyProp()!!
        val url = "https://maps.googleapis.com/maps/api/geocode/json?address=$encodedAddress&key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        response.use {
            check(it.isSuccessful) { "Google Geocoding API request failed: ${it.code} ${it.body?.string()}" }
            val body = it.body?.string() ?: return null
            return parseResponse(body)
        }
    }

    internal fun parseResponse(json: String): Coordinates? {
        val root = json.asRequiredJsonObject()
        val status = root.string("status") ?: return null
        if (status != "OK") return null

        val results = root.array("results")
        if (results == null || results.size == 0) return null

        val location = results[0].reqObj
            .obj("geometry")
            ?.obj("location")
            ?: return null

        val lat = location.double("lat") ?: return null
        val lng = location.double("lng") ?: return null
        return Coordinates(lat, lng)
    }
}
