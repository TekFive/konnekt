package org.tekfive.konnekt.location.service

import org.tekfive.keep.location.Address
import org.tekfive.keep.location.Coordinates

/**
 * Interface for geocoding implementations that convert addresses to geographic coordinates.
 *
 * Each provider reports whether it is [active] based on its configuration, and
 * declares a unique [name] for identification.
 */
interface CoordinatesProvider {

    val name: String

    val active: Boolean

    /**
     * Geocodes [address] to geographic coordinates.
     * Returns `null` if the address could not be resolved.
     */
    fun geocode(address: Address): Coordinates?
}
