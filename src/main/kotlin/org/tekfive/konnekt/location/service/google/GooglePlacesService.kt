package org.tekfive.konnekt.location.service.google

object GooglePlacesService {
    private val apiKeyProp = GoogleCoordinatesProvider.apiKeyProp

    @JvmStatic
    val apiKey: String?
        get() = if (apiKeyProp.isDefined) apiKeyProp() else null

    @JvmStatic
    val isActive: Boolean
        get() = apiKeyProp.isDefined
}
