package org.tekfive.konnekt.storage.providers

import org.tekfive.ack.Ack
import org.tekfive.konnekt.storage.KrateProvider
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isWritable

/**
 * A [KrateProvider] that stores blobs on the local filesystem.
 *
 * Buckets map to subdirectories under the root directory, and keys map to file paths
 * within those directories. Nested keys (e.g. "images/photo.png") create
 * intermediate directories automatically.
 *
 * Active when the `KRATE_FILE_SYSTEM_ROOT_DIR` property is defined.
 */
object FileSystemKrateProvider : KrateProvider {

    val rootDirAck = Ack.string("KRATE_FILE_SYSTEM_ROOT_DIR", "/tmp", description = "Root directory for the file-system storage provider.")

    override val name: String = "filesystem"

    private val rootDir: Path
        get() = Path.of(rootDirAck())

    override val active: Boolean
        get() = rootDir.isDirectory() && rootDir.isWritable()


    override fun put(bucket: String, key: String, input: InputStream, contentLength: Long, contentType: String?) {
        val file = resolve(bucket, key)
        file.parent.createDirectories()
        input.use { src ->
            Files.newOutputStream(file).use { out ->
                src.copyTo(out)
            }
        }
    }

    override fun get(bucket: String, key: String): InputStream? {
        val file = resolve(bucket, key)
        if (!file.exists()) return null
        return Files.newInputStream(file)
    }

    override fun delete(bucket: String, key: String): Boolean {
        val file = resolve(bucket, key)
        return file.deleteIfExists()
    }

    override fun exists(bucket: String, key: String): Boolean {
        return resolve(bucket, key).exists()
    }

    override fun list(bucket: String, prefix: String?): List<String> {
        val bucketDir = rootDir.resolve(bucket)
        if (!bucketDir.exists()) return emptyList()

        val keys = mutableListOf<String>()
        Files.walk(bucketDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .forEach { file ->
                    val relativePath = bucketDir.relativize(file).toString()
                    keys.add(relativePath)
                }
        }

        val filtered = if (prefix != null) {
            keys.filter { it.startsWith(prefix) }
        } else {
            keys
        }
        return filtered.sorted()
    }

    private fun resolve(bucket: String, key: String): Path {
        return rootDir.resolve(bucket).resolve(key)
    }
}
