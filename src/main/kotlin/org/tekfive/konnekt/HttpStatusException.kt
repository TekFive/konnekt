package org.tekfive.konnekt

import okhttp3.Response

class HttpStatusException(
    val statusCode: Int,
    val body: String?,
    message: String = "HTTP status $statusCode returned."
) : IllegalStateException(message) {
    constructor(response: Response) : this(response.code, response.body?.string())
}