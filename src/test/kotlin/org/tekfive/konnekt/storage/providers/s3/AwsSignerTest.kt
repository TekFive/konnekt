package org.tekfive.konnekt.storage.providers.s3

import java.net.URI
import kotlin.test.*

class AwsSignerTest {

    private val signer = AwsSigner(
        accessKeyId = "AKIAIOSFODNN7EXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region = "us-east-1",
    )

    @Test
    fun `sign produces authorization header`() {
        val uri = URI.create("https://examplebucket.s3.us-east-1.amazonaws.com/test.txt")
        val payloadHash = sha256Hex(ByteArray(0))

        val result = signer.sign("GET", uri, emptyMap(), payloadHash)

        assertTrue(result.authorization.startsWith("AWS4-HMAC-SHA256"))
        assertTrue(result.authorization.contains("Credential=AKIAIOSFODNN7EXAMPLE/"))
        assertTrue(result.authorization.contains("SignedHeaders="))
        assertTrue(result.authorization.contains("Signature="))
        assertTrue(result.date.matches(Regex("\\d{8}T\\d{6}Z")))
    }

    @Test
    fun `sign includes host in signed headers`() {
        val uri = URI.create("https://mybucket.s3.us-east-1.amazonaws.com/key")
        val payloadHash = sha256Hex(ByteArray(0))

        val result = signer.sign("GET", uri, emptyMap(), payloadHash)

        assertTrue(result.authorization.contains("host"))
    }

    @Test
    fun `sign includes custom headers`() {
        val uri = URI.create("https://mybucket.s3.us-east-1.amazonaws.com/key")
        val payloadHash = sha256Hex("hello".toByteArray())
        val headers = mapOf("content-type" to "text/plain")

        val result = signer.sign("PUT", uri, headers, payloadHash)

        assertTrue(result.authorization.contains("content-type"))
    }

    @Test
    fun `sha256Hex produces correct hash`() {
        val hash = sha256Hex(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `sha256Hex produces correct hash for data`() {
        val hash = sha256Hex("hello".toByteArray())
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    @Test
    fun `sign with non-standard port includes port in host`() {
        val uri = URI.create("http://localhost:9000/bucket/key")
        val payloadHash = sha256Hex(ByteArray(0))

        val result = signer.sign("GET", uri, emptyMap(), payloadHash)

        assertTrue(result.authorization.contains("Signature="))
    }
}
