package org.tekfive.konnekt.storage

import org.tekfive.kviash.exchange.ReturnErrorStatus
import org.tekfive.kviash.http.HttpResponse
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import kotlin.io.outputStream
import kotlin.jvm.Throws

/**
 * Central blob storage service that delegates to a single [KrateProvider].
 *
 * Usage:
 * ```
 * Krate.initialize(S3KrateProvider)
 * Krate.put("my-bucket", "path/to/file.txt", data)
 * val bytes = Krate.get("my-bucket", "path/to/file.txt")
 * ```
 */
object Krate {

    private var provider: KrateProvider? = null

    val isInitialized: Boolean
        get() = provider != null

    /**
     * Initializes Krate with [provider].
     *
     * @throws IllegalStateException if [provider] is not [KrateProvider.active]
     */
    fun initialize(provider: KrateProvider) {
        check(provider.active) { "KrateProvider '${provider.name}' is not active." }
        this.provider = provider
    }

    @Throws(IllegalStateException::class)
    private fun provider(): KrateProvider {
        return provider
            ?: error("Krate has not been initialized. Call Krate.initialize() first.")
    }

    @Throws(IllegalStateException::class)
    fun put(bucket: String, key: String, input: InputStream, contentLength: Long, contentType: String? = null) {
        provider().put(bucket, key, input, contentLength, contentType)
    }

    @Throws(IllegalStateException::class)
    fun putBytes(bucket: String, key: String, data: ByteArray, contentType: String? = null) {
        provider().putBytes(bucket, key, data, contentType)
    }

    @Throws(IllegalStateException::class)
    fun get(bucket: String, key: String): InputStream? {
        return provider().get(bucket, key)
    }

    @Throws(IllegalStateException::class)
    fun getBytes(bucket: String, key: String): ByteArray? {
        return provider().getBytes(bucket, key)
    }

    @Throws(IllegalStateException::class, ReturnErrorStatus::class)
    fun stream(bucket: String, key: String, filename: String, response:  HttpResponse) {
        if (!stream(bucket, key) {
            val encodedFilename = URLEncoder.encode(filename, Charsets.UTF_8).replace("+", "%20")
            response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encodedFilename")
            response.outputStream
        } ) {
            ReturnErrorStatus.onNotFound()
        }
    }

    @Throws(IllegalStateException::class)
    fun stream(bucket: String, key: String, output: () -> OutputStream): Boolean {
        return provider().stream(bucket, key, output)
    }

    @Throws(IllegalStateException::class)
    fun delete(bucket: String, key: String): Boolean {
        return provider().delete(bucket, key)
    }

    @Throws(IllegalStateException::class)
    fun exists(bucket: String, key: String): Boolean {
        return provider().exists(bucket, key)
    }

    @Throws(IllegalStateException::class)
    fun list(bucket: String, prefix: String? = null): List<String> {
        return provider().list(bucket, prefix)
    }

    fun reset() {
        provider = null
    }
}

/**
 * Discovers Kotlin object singletons registered in `META-INF/services` for the given type.
 *
 * Reads the standard service-provider configuration file and loads each listed class via its
 * Kotlin `INSTANCE` field. Classes whose dependencies are missing from the classpath are
 * silently skipped (supports optional providers).
 */
inline fun <reified T : Any> discoverProviders(): List<T> {
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
