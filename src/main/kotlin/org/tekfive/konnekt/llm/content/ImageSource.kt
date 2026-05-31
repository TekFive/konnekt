package org.tekfive.konnekt.llm.content

sealed class ImageSource {
    data class Base64(val mediaType: String, val data: String) : ImageSource()
    data class Url(val url: String) : ImageSource()
}
