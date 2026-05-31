package org.tekfive.konnekt.llm.content

sealed class DocumentSource {
    data class Base64(val mediaType: String, val data: String) : DocumentSource()
    data class Url(val url: String) : DocumentSource()
}
