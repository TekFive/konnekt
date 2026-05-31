package org.tekfive.konnekt.storage

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Interface for blob storage implementations (e.g., S3, local filesystem, GCS).
 *
 * Implementations are discovered at initialization via [java.util.ServiceLoader].
 * Each provider reports whether it is [active] based on its configuration, and
 * declares a unique [name] for identification.
 *
 * Providers must implement the [InputStream]-based [put] and [get] methods.
 * The [ByteArray] variants ([putBytes] and [getBytes]) delegate to them by default.
 */
interface KrateProvider {

    val name: String

    val active: Boolean

    fun put(bucket: String, key: String, input: InputStream, contentLength: Long, contentType: String? = null)

    fun putBytes(bucket: String, key: String, data: ByteArray, contentType: String? = null) {
        put(bucket, key, ByteArrayInputStream(data), data.size.toLong(), contentType)
    }

    fun get(bucket: String, key: String): InputStream?

    fun getBytes(bucket: String, key: String): ByteArray? {
        val stream = get(bucket, key) ?: return null
        return stream.use { it.readBytes() }
    }

    fun stream(bucket: String, key: String, output: () -> OutputStream): Boolean {
        val stream = get(bucket, key) ?: return false
        stream.use { src -> output().use {
            out -> src.copyTo(out) }
        }
        return true
    }

    fun delete(bucket: String, key: String): Boolean

    fun exists(bucket: String, key: String): Boolean

    fun list(bucket: String, prefix: String? = null): List<String>
}
