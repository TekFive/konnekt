package org.tekfive.konnekt.storage.providers.s3

import java.net.URI
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 signer for S3 REST API requests.
 */
internal class AwsSigner(
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val region: String,
    private val service: String = "s3",
) {

    companion object {
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }

    data class SignedHeaders(
        val authorization: String,
        val date: String,
        val contentSha256: String,
    )

    fun sign(
        method: String,
        uri: URI,
        headers: Map<String, String>,
        payloadHash: String,
    ): SignedHeaders {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val dateStamp = DATE_FORMAT.format(now)
        val amzDate = DATETIME_FORMAT.format(now)

        val allHeaders = headers.toMutableMap()
        allHeaders["host"] = uri.host + if (uri.port > 0 && uri.port != 443 && uri.port != 80) ":${uri.port}" else ""
        allHeaders["x-amz-date"] = amzDate
        allHeaders["x-amz-content-sha256"] = payloadHash

        val sortedHeaderNames = allHeaders.keys.map { it.lowercase() }.sorted()
        val signedHeadersStr = sortedHeaderNames.joinToString(";")

        val canonicalHeaders = sortedHeaderNames.joinToString("") { name ->
            val value = allHeaders.entries.first { it.key.equals(name, ignoreCase = true) }.value
            "$name:${value.trim()}\n"
        }

        val canonicalPath = uri.rawPath.ifEmpty { "/" }
        val canonicalQueryString = uri.rawQuery ?: ""

        val canonicalRequest = listOf(
            method,
            canonicalPath,
            canonicalQueryString,
            canonicalHeaders,
            signedHeadersStr,
            payloadHash,
        ).joinToString("\n")

        val credentialScope = "$dateStamp/$region/$service/aws4_request"

        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest.toByteArray()),
        ).joinToString("\n")

        val signingKey = deriveSigningKey(dateStamp)
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorization = "$ALGORITHM Credential=$accessKeyId/$credentialScope, " +
            "SignedHeaders=$signedHeadersStr, Signature=$signature"

        return SignedHeaders(
            authorization = authorization,
            date = amzDate,
            contentSha256 = payloadHash,
        )
    }

    private fun deriveSigningKey(dateStamp: String): ByteArray {
        val kDate = hmacSha256("AWS4$secretAccessKey".toByteArray(), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }
}

internal fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(data)
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun hmacSha256(key: ByteArray, data: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.toByteArray())
}

private fun hmacSha256Hex(key: ByteArray, data: String): String {
    return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
}
