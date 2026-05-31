package org.tekfive.konnekt.storage.providers

import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FileSystemKrateProviderTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("krate-fs-test")
        AckRegistry.clear()
        AckRegistry.addSource(MapSource(mapOf(FileSystemKrateProvider.rootDirAck.name to tempDir.toString())))
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
        AckRegistry.clear()
    }

    @Test
    fun `active when root dir property is defined`() {
        assertTrue(FileSystemKrateProvider.active)
    }

    @Test
    fun `put and get round-trips data`() {
        val data = "hello filesystem".toByteArray()
        FileSystemKrateProvider.putBytes("bucket", "file.txt", data)

        val result = FileSystemKrateProvider.getBytes("bucket", "file.txt")
        assertContentEquals(data, result)
    }

    @Test
    fun `put with input stream`() {
        val data = "stream data".toByteArray()
        val stream = ByteArrayInputStream(data)
        FileSystemKrateProvider.put("bucket", "stream.txt", stream, data.size.toLong())

        val result = FileSystemKrateProvider.getBytes("bucket", "stream.txt")
        assertContentEquals(data, result)
    }

    @Test
    fun `get returns null for missing key`() {
        assertNull(FileSystemKrateProvider.get("bucket", "missing"))
    }

    @Test
    fun `get returns input stream`() {
        val data = "stream content".toByteArray()
        FileSystemKrateProvider.putBytes("bucket", "file.bin", data)

        val stream = FileSystemKrateProvider.get("bucket", "file.bin")
        assertNotNull(stream)
        assertContentEquals(data, stream.use { it.readBytes() })
    }

    @Test
    fun `put creates intermediate directories for nested keys`() {
        val data = "nested".toByteArray()
        FileSystemKrateProvider.putBytes("bucket", "a/b/c/file.txt", data)

        val result = FileSystemKrateProvider.getBytes("bucket", "a/b/c/file.txt")
        assertContentEquals(data, result)
    }

    @Test
    fun `delete removes file`() {
        FileSystemKrateProvider.putBytes("bucket", "file.txt", "data".toByteArray())
        assertTrue(FileSystemKrateProvider.exists("bucket", "file.txt"))

        val deleted = FileSystemKrateProvider.delete("bucket", "file.txt")
        assertTrue(deleted)
        assertFalse(FileSystemKrateProvider.exists("bucket", "file.txt"))
    }

    @Test
    fun `delete returns false for missing key`() {
        assertFalse(FileSystemKrateProvider.delete("bucket", "missing"))
    }

    @Test
    fun `exists returns true for existing key`() {
        FileSystemKrateProvider.putBytes("bucket", "file.txt", "data".toByteArray())
        assertTrue(FileSystemKrateProvider.exists("bucket", "file.txt"))
    }

    @Test
    fun `exists returns false for missing key`() {
        assertFalse(FileSystemKrateProvider.exists("bucket", "missing"))
    }

    @Test
    fun `list returns keys in bucket`() {
        FileSystemKrateProvider.putBytes("bucket", "a.txt", "a".toByteArray())
        FileSystemKrateProvider.putBytes("bucket", "b.txt", "b".toByteArray())
        FileSystemKrateProvider.putBytes("other", "c.txt", "c".toByteArray())

        val keys = FileSystemKrateProvider.list("bucket")
        assertEquals(listOf("a.txt", "b.txt"), keys)
    }

    @Test
    fun `list with prefix filters keys`() {
        FileSystemKrateProvider.putBytes("bucket", "images/a.png", "a".toByteArray())
        FileSystemKrateProvider.putBytes("bucket", "images/b.png", "b".toByteArray())
        FileSystemKrateProvider.putBytes("bucket", "docs/c.pdf", "c".toByteArray())

        val keys = FileSystemKrateProvider.list("bucket", "images/")
        assertEquals(listOf("images/a.png", "images/b.png"), keys)
    }

    @Test
    fun `list returns empty for missing bucket`() {
        assertEquals(emptyList(), FileSystemKrateProvider.list("missing"))
    }

    @Test
    fun `put overwrites existing file`() {
        FileSystemKrateProvider.putBytes("bucket", "file.txt", "original".toByteArray())
        FileSystemKrateProvider.putBytes("bucket", "file.txt", "updated".toByteArray())

        val result = FileSystemKrateProvider.getBytes("bucket", "file.txt")
        assertEquals("updated", result?.let { String(it) })
    }

    @Test
    fun `name is filesystem`() {
        assertEquals("filesystem", FileSystemKrateProvider.name)
    }
}
