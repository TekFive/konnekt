package org.tekfive.konnekt

import com.google.crypto.tink.aead.AeadConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import org.tekfive.keep.encryption.DatabaseEncryptionProvider
import org.tekfive.keep.encryption.EncryptionKeysetMode
import org.tekfive.keep.encryption.KeysetIO
import org.tekfive.keep.encryption.KeysetLoader
import org.tekfive.keep.encryption.KeysetTemplate
import java.nio.file.Files

/**
 * Singleton PostgreSQL container for konnekt tests. Started once on first access.
 */
object TestDatabase {
    private val container = PostgreSQLContainer("postgres:17-alpine").apply {
        withDatabaseName("konnekt_test")
        withUsername("test")
        withPassword("test")
        start()
    }

    init {
        // Encrypted columns (e.g. QueuedMessageTable, MessageReceiptTable) resolve their
        // Aead at class-init time, so the encryption provider must be ready before any
        // test touches those tables.
        AeadConfig.register()
        DatabaseEncryptionProvider.resetForTesting()
        val keysetPath = Files.createTempDirectory("konnekt-test-keyset").resolve("keyset.json")
        KeysetIO.write(KeysetTemplate.generateNewKeysetHandle(), keysetPath)
        DatabaseEncryptionProvider.configure(
            KeysetLoader.Config(
                mode = EncryptionKeysetMode.PLAINTEXT,
                file = keysetPath,
            )
        )
        DatabaseEncryptionProvider.ensureInitialized()
    }

    fun connect(): Database {
        val db = Database.connect(
            url = container.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = container.username,
            password = container.password,
        )
        transaction {
            exec("CREATE EXTENSION IF NOT EXISTS citext")
        }
        return db
    }
}
