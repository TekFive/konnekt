package org.tekfive.konnekt.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.*

private class TestKrateProvider(
    override val name: String = "test",
    override val active: Boolean = true,
) : KrateProvider {

    private val store = mutableMapOf<String, MutableMap<String, ByteArray>>()

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
}

class KrateTest {

    @BeforeTest
    fun setUp() {
        Krate.reset()
    }

    @Test
    fun `initialize with active provider`() {
        Krate.initialize(TestKrateProvider())
        assertTrue(Krate.isInitialized)
    }

    @Test
    fun `initialize fails with inactive provider`() {
        val inactive = TestKrateProvider(active = false)

        val ex = assertFailsWith<IllegalStateException> {
            Krate.initialize(inactive)
        }
        assertTrue(ex.message!!.contains("not active"))
    }

    @Test
    fun `putBytes and get round-trips data`() {
        Krate.initialize(TestKrateProvider())

        val data = "hello blob".toByteArray()
        Krate.putBytes("bucket", "file.txt", data)

        val result = Krate.getBytes("bucket", "file.txt")
        assertContentEquals(data, result)
    }

    @Test
    fun `put with input stream`() {
        Krate.initialize(TestKrateProvider())

        val data = "stream data".toByteArray()
        val stream = ByteArrayInputStream(data)
        Krate.put("bucket", "stream.txt", stream, data.size.toLong())

        val result = Krate.getBytes("bucket", "stream.txt")
        assertContentEquals(data, result)
    }

    @Test
    fun `get returns null for missing key`() {
        Krate.initialize(TestKrateProvider())

        assertNull(Krate.getBytes("bucket", "missing"))
    }

    @Test
    fun `getStream returns input stream`() {
        Krate.initialize(TestKrateProvider())

        val data = "stream content".toByteArray()
        Krate.putBytes("bucket", "file.bin", data)

        val stream = Krate.get("bucket", "file.bin")
        assertNotNull(stream)
        assertContentEquals(data, stream.readBytes())
    }

    @Test
    fun `getStream returns null for missing key`() {
        Krate.initialize(TestKrateProvider())

        assertNull(Krate.get("bucket", "missing"))
    }

    @Test
    fun `delete removes blob`() {
        Krate.initialize(TestKrateProvider())

        Krate.putBytes("bucket", "file.txt", "data".toByteArray())
        assertTrue(Krate.exists("bucket", "file.txt"))

        val deleted = Krate.delete("bucket", "file.txt")
        assertTrue(deleted)
        assertFalse(Krate.exists("bucket", "file.txt"))
    }

    @Test
    fun `delete returns false for missing key`() {
        Krate.initialize(TestKrateProvider())

        assertFalse(Krate.delete("bucket", "missing"))
    }

    @Test
    fun `exists returns true for existing key`() {
        Krate.initialize(TestKrateProvider())

        Krate.putBytes("bucket", "file.txt", "data".toByteArray())
        assertTrue(Krate.exists("bucket", "file.txt"))
    }

    @Test
    fun `exists returns false for missing key`() {
        Krate.initialize(TestKrateProvider())

        assertFalse(Krate.exists("bucket", "missing"))
    }

    @Test
    fun `list returns keys in bucket`() {
        Krate.initialize(TestKrateProvider())

        Krate.putBytes("bucket", "a.txt", "a".toByteArray())
        Krate.putBytes("bucket", "b.txt", "b".toByteArray())
        Krate.putBytes("other", "c.txt", "c".toByteArray())

        val keys = Krate.list("bucket")
        assertEquals(listOf("a.txt", "b.txt"), keys)
    }

    @Test
    fun `list with prefix filters keys`() {
        Krate.initialize(TestKrateProvider())

        Krate.putBytes("bucket", "images/a.png", "a".toByteArray())
        Krate.putBytes("bucket", "images/b.png", "b".toByteArray())
        Krate.putBytes("bucket", "docs/c.pdf", "c".toByteArray())

        val keys = Krate.list("bucket", "images/")
        assertEquals(listOf("images/a.png", "images/b.png"), keys)
    }

    @Test
    fun `list returns empty for missing bucket`() {
        Krate.initialize(TestKrateProvider())

        assertEquals(emptyList(), Krate.list("missing"))
    }

    @Test
    fun `operations fail before initialization`() {
        assertFailsWith<IllegalStateException> {
            Krate.getBytes("bucket", "key")
        }
    }

    @Test
    fun `reset clears state`() {
        Krate.initialize(TestKrateProvider())
        assertTrue(Krate.isInitialized)

        Krate.reset()
        assertFalse(Krate.isInitialized)
    }

    @Test
    fun `stream writes data to output stream and returns true`() {
        Krate.initialize(TestKrateProvider())

        val data = "stream to output".toByteArray()
        Krate.putBytes("bucket", "file.bin", data)

        val output = ByteArrayOutputStream()
        val found = Krate.stream("bucket", "file.bin") { output }

        assertTrue(found)
        assertContentEquals(data, output.toByteArray())
    }

    @Test
    fun `stream returns false for missing key`() {
        Krate.initialize(TestKrateProvider())

        var outputCreated = false
        val found = Krate.stream("bucket", "missing") {
            outputCreated = true
            ByteArrayOutputStream()
        }

        assertFalse(found)
        assertFalse(outputCreated)
    }

    @Test
    fun `putBytes delegates to put stream`() {
        val provider = TestKrateProvider()
        Krate.initialize(provider)

        Krate.putBytes("bucket", "key", "delegated".toByteArray())

        val stored = provider.getBytes("bucket", "key")
        assertNotNull(stored)
        assertEquals("delegated", String(stored))
    }
}
