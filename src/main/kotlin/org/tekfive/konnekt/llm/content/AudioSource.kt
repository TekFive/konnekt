package org.tekfive.konnekt.llm.content

sealed class AudioSource {
    data class Base64(val mediaType: String, val data: String) : AudioSource()
    data class Url(val url: String) : AudioSource()
}