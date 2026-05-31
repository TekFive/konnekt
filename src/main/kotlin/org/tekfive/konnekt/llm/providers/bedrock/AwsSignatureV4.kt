package org.tekfive.konnekt.llm.providers.bedrock

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal AWS Signature V4 implementation for signing Bedrock API requests.
 *
 * See: https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
 */
internal object AwsSignatureV4 {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "bedrock"

    private val ISO8601_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
        .withZone(ZoneOffset.UTC)

    /**
     * Signs an HTTP request and returns the headers that must be added.
     *
     * @param method HTTP method (e.g., "POST")
     * @param url Full request URL
     * @param headers Existing headers as name-value pairs
     * @param body Request body bytes
     * @param accessKeyId AWS access key ID
     * @param secretAccessKey AWS secret access key
     * @param region AWS region (e.g., "us-east-1")
     * @param now Timestamp for signing (defaults to current time)
     * @return Map of headers to add to the request (Authorization, X-Amz-Date, etc.)
     */
    fun sign(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        accessKeyId: String,
        secretAccessKey: String,
        region: String,
        now: Instant = Instant.now(),
    ): Map<String, String> {
        val parsedUrl = URI(url)
        val host = parsedUrl.host
        val path = if (parsedUrl.path.isNullOrEmpty()) "/" else parsedUrl.path
        val query = parsedUrl.query ?: ""

        val amzDate = ISO8601_FORMAT.format(now)
        val dateStamp = DATE_FORMAT.format(now)

        val payloadHash = sha256Hex(body)

        // Build signed headers map (must include host, x-amz-date, and content-type if present)
        val signedHeaders = mutableMapOf<String, String>()
        signedHeaders["host"] = host
        signedHeaders["x-amz-date"] = amzDate
        for ((key, value) in headers) {
            val lowerKey = key.lowercase()
            if (lowerKey == "content-type") {
                signedHeaders[lowerKey] = value
            }
        }

        val sortedHeaderKeys = signedHeaders.keys.sorted()
        val canonicalHeaders = sortedHeaderKeys.joinToString("") { "$it:${signedHeaders[it]!!.trim()}\n" }
        val signedHeadersString = sortedHeaderKeys.joinToString(";")

        // Canonical request
        val canonicalRequest = listOf(
            method,
            uriEncode(path, isPath = true),
            canonicalQueryString(query),
            canonicalHeaders,
            signedHeadersString,
            payloadHash,
        ).joinToString("\n")

        // Credential scope
        val credentialScope = "$dateStamp/$region/$SERVICE/aws4_request"

        // String to sign
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n")

        // Signing key
        val signingKey = getSignatureKey(secretAccessKey, dateStamp, region, SERVICE)

        // Signature
        val signature = hmacSha256Hex(signingKey, stringToSign)

        // Authorization header
        val authorization = "$ALGORITHM Credential=$accessKeyId/$credentialScope, " +
            "SignedHeaders=$signedHeadersString, Signature=$signature"

        return mapOf(
            "Authorization" to authorization,
            "X-Amz-Date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
        )
    }

    /**
     * Extracts the AWS region from a Bedrock runtime URL.
     * Expected format: https://bedrock-runtime.{region}.amazonaws.com
     */
    fun extractRegion(baseUrl: String): String {
        val host = URI(baseUrl).host
        // Expected: bedrock-runtime.us-east-1.amazonaws.com
        val parts = host.split(".")
        if (parts.size >= 3 && parts[0] == "bedrock-runtime") {
            return parts[1]
        }
        throw IllegalArgumentException(
            "Cannot extract region from Bedrock URL: $baseUrl. " +
            "Expected format: https://bedrock-runtime.{region}.amazonaws.com"
        )
    }

    private fun getSignatureKey(key: String, dateStamp: String, region: String, service: String): ByteArray {
        val kDate = hmacSha256("AWS4$key".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).toHexString()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).toHexString()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun canonicalQueryString(query: String): String {
        if (query.isEmpty()) return ""
        return query.split("&")
            .map { param ->
                val parts = param.split("=", limit = 2)
                val key = uriEncode(parts[0])
                val value = if (parts.size > 1) uriEncode(parts[1]) else ""
                "$key=$value"
            }
            .sorted()
            .joinToString("&")
    }

    private fun uriEncode(input: String, isPath: Boolean = false): String {
        val sb = StringBuilder()
        for (ch in input) {
            when {
                ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> sb.append(ch)
                isPath && ch == '/' -> sb.append(ch)
                else -> {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append("%%%02X".format(b))
                    }
                }
            }
        }
        return sb.toString()
    }
}
