package org.tekfive.konnekt.message.email

fun interface EmailProviderTypeConfigurationResolver {
    fun resolve(emailProviderConfigurationId: String): EmailProviderTypeConfiguration?
}
