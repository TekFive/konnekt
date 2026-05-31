package org.tekfive.konnekt.location.service

import org.tekfive.keep.location.Address
import org.tekfive.keep.location.Coordinates

/**
 * Central geocoding service that discovers and delegates to [CoordinatesProvider]
 * implementations.
 *
 * On [initialize], all known provider objects are checked and those reporting
 * [CoordinatesProvider.active] are retained. If multiple active providers exist,
 * a [defaultProviderName] can be specified; otherwise the first active provider
 * is used as the default.
 *
 * Usage:
 * ```
 * CoordinatesService.initialize()
 * val coords = CoordinatesService.geocode(address)
 * ```
 *
 * To target a specific provider when multiple are active:
 * ```
 * CoordinatesService.provider("google").geocode(address)
 * ```
 */
object CoordinatesService {

    private var providers: Map<String, CoordinatesProvider> = emptyMap()
    private var defaultProvider: CoordinatesProvider? = null

    val activeProviders: Collection<CoordinatesProvider>
        get() = providers.values

    val isInitialized: Boolean
        get() = providers.isNotEmpty()

    /**
     * Discovers [CoordinatesProvider] implementations registered in
     * `META-INF/services/org.tekfive.konnekt.location.service.CoordinatesProvider` and
     * initializes with those that are [CoordinatesProvider.active].
     */
    fun initialize(defaultProviderName: String? = null) {
        val discoveredProviders = discoverProviders<CoordinatesProvider>()
        initializeWith(discoveredProviders, defaultProviderName)
    }

    /**
     * Initializes with explicitly provided [CoordinatesProvider] instances.
     * Useful for testing or when providers are constructed manually.
     */
    fun initialize(vararg providers: CoordinatesProvider, defaultProviderName: String? = null) {
        initializeWith(providers.toList(), defaultProviderName)
    }

    private fun initializeWith(allProviders: List<CoordinatesProvider>, defaultProviderName: String?) {
        val activeProviders = allProviders.filter { it.active }

        check(activeProviders.isNotEmpty()) { "No active CoordinatesProvider implementations found." }

        val duplicateNames = activeProviders.groupBy { it.name }.filter { it.value.size > 1 }.keys
        check(duplicateNames.isEmpty()) {
            "Duplicate CoordinatesProvider names: ${duplicateNames.joinToString()}"
        }

        providers = activeProviders.associateBy { it.name }

        defaultProvider = if (defaultProviderName != null) {
            providers[defaultProviderName]
                ?: error("Default provider '$defaultProviderName' is not among active providers: ${providers.keys}")
        } else {
            activeProviders.first()
        }
    }

    fun provider(name: String): CoordinatesProvider {
        return providers[name]
            ?: error("No active CoordinatesProvider with name '$name'. Active: ${providers.keys}")
    }

    private fun default(): CoordinatesProvider {
        return defaultProvider
            ?: error("CoordinatesService has not been initialized. Call CoordinatesService.initialize() first.")
    }

    fun geocode(address: Address): Coordinates? {
        return default().geocode(address)
    }

    fun reset() {
        providers = emptyMap()
        defaultProvider = null
    }
}

/**
 * Discovers Kotlin object singletons registered in `META-INF/services` for the given type.
 *
 * Reads the standard service-provider configuration file and loads each listed class via its
 * Kotlin `INSTANCE` field. Classes whose dependencies are missing from the classpath are
 * silently skipped (supports optional providers).
 */
internal inline fun <reified T : Any> discoverProviders(): List<T> {
    val serviceFile = "META-INF/services/${T::class.java.name}"
    val classLoader = Thread.currentThread().contextClassLoader ?: T::class.java.classLoader
    val urls = classLoader.getResources(serviceFile).toList()

    val providers = mutableListOf<T>()
    for (url in urls) {
        url.openStream().bufferedReader().useLines { lines ->
            for (className in lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
                try {
                    val clazz = Class.forName(className, true, classLoader)
                    @Suppress("UNCHECKED_CAST")
                    val instance = clazz.getField("INSTANCE").get(null) as T
                    providers.add(instance)
                } catch (_: ClassNotFoundException) {
                } catch (_: NoClassDefFoundError) {
                }
            }
        }
    }
    return providers
}
