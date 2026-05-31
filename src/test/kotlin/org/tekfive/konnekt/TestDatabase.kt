package org.tekfive.konnekt

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer

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
