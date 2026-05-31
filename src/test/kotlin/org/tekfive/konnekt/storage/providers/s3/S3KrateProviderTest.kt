package org.tekfive.konnekt.storage.providers.s3

import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import kotlin.test.*

class S3KrateProviderTest {

    @BeforeTest
    fun setUp() {
        AckRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        AckRegistry.clear()
    }

    @Test
    fun `parseKeys extracts keys from ListObjectsV2 response`() {
        val xml = """
            <ListBucketResult>
                <Name>bucket</Name>
                <KeyCount>3</KeyCount>
                <Contents><Key>file1.txt</Key></Contents>
                <Contents><Key>dir/file2.txt</Key></Contents>
                <Contents><Key>file3.txt</Key></Contents>
            </ListBucketResult>
        """.trimIndent()

        val keys = S3KrateProvider.parseKeys(xml)
        assertEquals(listOf("file1.txt", "dir/file2.txt", "file3.txt"), keys)
    }

    @Test
    fun `parseKeys returns empty for no contents`() {
        val xml = """
            <ListBucketResult>
                <Name>bucket</Name>
                <KeyCount>0</KeyCount>
            </ListBucketResult>
        """.trimIndent()

        val keys = S3KrateProvider.parseKeys(xml)
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `parseKeys handles XML entities in keys`() {
        val xml = """
            <ListBucketResult>
                <Contents><Key>files/name&amp;value.txt</Key></Contents>
            </ListBucketResult>
        """.trimIndent()

        val keys = S3KrateProvider.parseKeys(xml)
        assertEquals(listOf("files/name&value.txt"), keys)
    }

    @Test
    fun `parseNextContinuationToken extracts token`() {
        val xml = """
            <ListBucketResult>
                <IsTruncated>true</IsTruncated>
                <NextContinuationToken>abc123</NextContinuationToken>
                <Contents><Key>file.txt</Key></Contents>
            </ListBucketResult>
        """.trimIndent()

        val token = S3KrateProvider.parseNextContinuationToken(xml)
        assertEquals("abc123", token)
    }

    @Test
    fun `parseNextContinuationToken returns null when not present`() {
        val xml = """
            <ListBucketResult>
                <IsTruncated>false</IsTruncated>
                <Contents><Key>file.txt</Key></Contents>
            </ListBucketResult>
        """.trimIndent()

        val token = S3KrateProvider.parseNextContinuationToken(xml)
        assertNull(token)
    }

    @Test
    fun `name is s3`() {
        assertEquals("s3", S3KrateProvider.name)
    }

    @Test
    fun `active when all required properties are defined`() {
        AckRegistry.addSource(MapSource(mapOf(
            S3KrateProvider.accessKeyIdProp.name to "AKIATEST",
            S3KrateProvider.secretAccessKeyProp.name to "secret",
            S3KrateProvider.regionProp.name to "us-east-1",
        )))
        assertTrue(S3KrateProvider.active)
    }

    @Test
    fun `inactive when access key is missing`() {
        AckRegistry.addSource(MapSource(mapOf(
            S3KrateProvider.secretAccessKeyProp.name to "secret",
            S3KrateProvider.regionProp.name to "us-east-1",
        )))
        assertFalse(S3KrateProvider.active)
    }

    @Test
    fun `inactive when secret key is missing`() {
        AckRegistry.addSource(MapSource(mapOf(
            S3KrateProvider.accessKeyIdProp.name to "AKIATEST",
            S3KrateProvider.regionProp.name to "us-east-1",
        )))
        assertFalse(S3KrateProvider.active)
    }

    @Test
    fun `inactive when region is missing`() {
        AckRegistry.addSource(MapSource(mapOf(
            S3KrateProvider.accessKeyIdProp.name to "AKIATEST",
            S3KrateProvider.secretAccessKeyProp.name to "secret",
        )))
        assertFalse(S3KrateProvider.active)
    }

    @Test
    fun `inactive when no properties defined`() {
        AckRegistry.addSource(MapSource(emptyMap()))
        assertFalse(S3KrateProvider.active)
    }
}
