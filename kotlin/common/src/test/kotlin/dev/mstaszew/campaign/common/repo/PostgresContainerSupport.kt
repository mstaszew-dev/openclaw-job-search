package dev.mstaszew.campaign.common.repo

import org.testcontainers.containers.PostgreSQLContainer

/**
 * Singleton Postgres for all repo integration tests in this module; Flyway
 * migrates the real V1 schema. Requires Docker: tests self-skip without it
 * (CI runs them on every push).
 */
object PostgresContainerSupport {
    val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("campaign")
        .withUsername("campaign")
        .withPassword("campaign")
        .apply { start() }
}
