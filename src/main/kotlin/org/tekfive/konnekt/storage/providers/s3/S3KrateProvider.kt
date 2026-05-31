package org.tekfive.konnekt.storage.providers.s3

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.tekfive.ack.Ack
import org.tekfive.konnekt.storage.KrateProvider
import java.io.InputStream
import java.net.URI

/**
 * A [KrateProvider] backed by Amazon S3 (or any S3-compatible API such as MinIO, R2, etc.).
 *
 * Uses OkHttp and AWS Signature V4 signing directly — no AWS SDK required.
 *
 * Active when `KRATE_S3_ACCESS_KEY_ID`, `KRATE_S3_SECRET_ACCESS_KEY`, and `KRATE_S3_REGION` are all defined.
 *
 * Optional properties:
 * - `KRATE_S3_ENDPOINT` — custom endpoint URL for S3-compatible services
 * - `KRATE_S3_PATH_STYLE_ACCESS` — use path-style URLs instead of virtual-hosted-style (default: false)
 */
object S3KrateProvider : KrateProvider {

    val accessKeyIdProp = Ack.string("KRATE_S3_ACCESS_KEY_ID", description = "AWS access key id for the S3 storage provider.")
    val secretAccessKeyProp = Ack.secret("KRATE_S3_SECRET_ACCESS_KEY", description = "AWS secret access key for the S3 storage provider.")
    val regionProp = Ack.string("KRATE_S3_REGION", description = "AWS region for the S3 storage provider.")
    val endpointProp = Ack.string("KRATE_S3_ENDPOINT", description = "Custom endpoint URL for an S3-compatible store.")
    val pathStyleAccessProp = Ack.boolean("KRATE_S3_PATH_STYLE_ACCESS", description = "Whether to use path-style access for the S3 storage provider.")

    override val name: String = "s3"

    override val active: Boolean
        get() = accessKeyIdProp.isDefined && secretAccessKeyProp.isDefined && regionProp.isDefined

    private val client by lazy { OkHttpClient() }

    private fun signer() = AwsSigner(accessKeyIdProp()!!, secretAccessKeyProp()!!, regionProp()!!)

    private val pathStyleAccess: Boolean
        get() = pathStyleAccessProp() ?: false

    private val region: String
        get() = regionProp()!!

    private val endpoint: String?
        get() = endpointProp()

    override fun put(bucket: String, key: String, input: InputStream, contentLength: Long, contentType: String?) {
        val body = input.use { it.readBytes() }
        val mediaType = (contentType ?: "application/octet-stream").toMediaTypeOrNull()
        val payloadHash = sha256Hex(body)

        val uri = buildUri(bucket, key)
        val headers = mutableMapOf<String, String>()
        if (contentType != null) {
            headers["content-type"] = contentType
        }

        val signed = signer().sign("PUT", uri, headers, payloadHash)

        val request = Request.Builder()
            .url(uri.toString())
            .put(body.toRequestBody(mediaType))
            .header("Authorization", signed.authorization)
            .header("x-amz-date", signed.date)
            .header("x-amz-content-sha256", signed.contentSha256)
            .build()

        val response = client.newCall(request).execute()
        response.use {
            check(it.isSuccessful) { "S3 PUT failed: ${it.code} ${it.body?.string()}" }
        }
    }

    override fun get(bucket: String, key: String): InputStream? {
        val uri = buildUri(bucket, key)
        val payloadHash = sha256Hex(ByteArray(0))
        val signed = signer().sign("GET", uri, emptyMap(), payloadHash)

        val request = Request.Builder()
            .url(uri.toString())
            .get()
            .header("Authorization", signed.authorization)
            .header("x-amz-date", signed.date)
            .header("x-amz-content-sha256", signed.contentSha256)
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 404) {
            response.close()
            return null
        }
        check(response.isSuccessful) { "S3 GET failed: ${response.code} ${response.body?.string()}" }
        return response.body?.byteStream()
    }

    override fun delete(bucket: String, key: String): Boolean {
        val uri = buildUri(bucket, key)
        val payloadHash = sha256Hex(ByteArray(0))
        val signed = signer().sign("DELETE", uri, emptyMap(), payloadHash)

        val request = Request.Builder()
            .url(uri.toString())
            .delete()
            .header("Authorization", signed.authorization)
            .header("x-amz-date", signed.date)
            .header("x-amz-content-sha256", signed.contentSha256)
            .build()

        val response = client.newCall(request).execute()
        response.use {
            return it.code == 204
        }
    }

    override fun exists(bucket: String, key: String): Boolean {
        val uri = buildUri(bucket, key)
        val payloadHash = sha256Hex(ByteArray(0))
        val signed = signer().sign("HEAD", uri, emptyMap(), payloadHash)

        val request = Request.Builder()
            .url(uri.toString())
            .head()
            .header("Authorization", signed.authorization)
            .header("x-amz-date", signed.date)
            .header("x-amz-content-sha256", signed.contentSha256)
            .build()

        val response = client.newCall(request).execute()
        response.use {
            return it.isSuccessful
        }
    }

    override fun list(bucket: String, prefix: String?): List<String> {
        val keys = mutableListOf<String>()
        var continuationToken: String? = null

        do {
            val queryParts = mutableListOf("list-type=2")
            if (prefix != null) queryParts.add("prefix=${uriEncode(prefix)}")
            if (continuationToken != null) queryParts.add("continuation-token=${uriEncode(continuationToken)}")
            val query = queryParts.joinToString("&")

            val uri = buildUri(bucket, queryString = query)
            val payloadHash = sha256Hex(ByteArray(0))
            val signed = signer().sign("GET", uri, emptyMap(), payloadHash)

            val request = Request.Builder()
                .url(uri.toString())
                .get()
                .header("Authorization", signed.authorization)
                .header("x-amz-date", signed.date)
                .header("x-amz-content-sha256", signed.contentSha256)
                .build()

            val response = client.newCall(request).execute()
            val xml = response.use {
                check(it.isSuccessful) { "S3 LIST failed: ${it.code} ${it.body?.string()}" }
                it.body?.string() ?: ""
            }

            keys.addAll(parseKeys(xml))
            continuationToken = parseNextContinuationToken(xml)
        } while (continuationToken != null)

        return keys.sorted()
    }

    private fun buildUri(bucket: String, key: String? = null, queryString: String? = null): URI {
        val endpoint = this.endpoint
        val region = this.region
        val baseUrl = endpoint ?: "https://s3.$region.amazonaws.com"
        val path = when {
            pathStyleAccess && key != null -> "/$bucket/${uriEncodePath(key)}"
            pathStyleAccess -> "/$bucket"
            key != null -> "/${uriEncodePath(key)}"
            else -> "/"
        }
        val host = if (!pathStyleAccess && endpoint == null) {
            "https://$bucket.s3.$region.amazonaws.com"
        } else {
            baseUrl
        }
        val qs = if (queryString != null) "?$queryString" else ""
        return URI.create("$host$path$qs")
    }

    internal fun parseKeys(xml: String): List<String> {
        val keys = mutableListOf<String>()
        val pattern = Regex("<Key>(.*?)</Key>")
        for (match in pattern.findAll(xml)) {
            keys.add(xmlUnescape(match.groupValues[1]))
        }
        return keys
    }

    internal fun parseNextContinuationToken(xml: String): String? {
        val match = Regex("<NextContinuationToken>(.*?)</NextContinuationToken>").find(xml)
        return match?.groupValues?.get(1)?.let { xmlUnescape(it) }
    }

    private fun xmlUnescape(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun uriEncode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun uriEncodePath(path: String): String {
        return path.split("/").joinToString("/") { uriEncode(it) }
    }
}
