package org.tekfive.konnekt.storage.providers

import org.tekfive.ack.Ack
import org.tekfive.konnekt.storage.KrateProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.logging.Logger

/**
 * A [KrateProvider] that stores blobs in memory.
 *
 * **WARNING: This provider should only be used in test and development environments.**
 * Data is not persisted and will be lost when the JVM exits.
 *
 * Active when the `KRATE_MEMORY_ENABLED` property is set to `true`.
 */
object MemoryKrateProvider : KrateProvider {

    private val log = Logger.getLogger(MemoryKrateProvider::class.java.name)

    val enabledProp = Ack.boolean("KRATE_MEMORY_ENABLED", description = "Whether the in-memory storage (krate) provider is enabled.")

    private val store = mutableMapOf<String, MutableMap<String, ByteArray>>()

    override val name: String = "memory"

    override val active: Boolean
        get() {
            val isActive = enabledProp() == true
            if (isActive) {
                log.warning("MemoryKrateProvider is active. This should only be used in test and development environments.")
            }
            return isActive
        }

    override fun put(bucket: String, key: String, input: InputStream, contentLength: Long, contentType: String?) {
        store.getOrPut(bucket) { mutableMapOf() }[key] = input.use { it.readBytes() }
    }

    override fun get(bucket: String, key: String): InputStream? {
        val bytes = store[bucket]?.get(key) ?: return null
        return ByteArrayInputStream(bytes)
    }

    override fun delete(bucket: String, key: String): Boolean {
        return store[bucket]?.remove(key) != null
    }

    override fun exists(bucket: String, key: String): Boolean {
        return store[bucket]?.containsKey(key) == true
    }

    override fun list(bucket: String, prefix: String?): List<String> {
        val bucketStore = store[bucket] ?: return emptyList()
        return if (prefix != null) {
            bucketStore.keys.filter { it.startsWith(prefix) }.sorted()
        } else {
            bucketStore.keys.sorted()
        }
    }

    fun clear() {
        store.clear()
    }
}
