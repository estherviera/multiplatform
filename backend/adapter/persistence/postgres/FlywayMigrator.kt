package com.nexora.backend.adapter.persistence.postgres

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.output.MigrateResult
import org.flywaydb.core.api.output.ValidateResult
import org.postgresql.ds.PGSimpleDataSource

data class MigrationSummary(
    val schema: String,
    val migrationsExecuted: Int,
    val targetSchemaVersion: String?,
)

class FlywayMigrator(
    private val config: FlywayConfig,
) {
    private fun buildFlyway(): Flyway {
        val dataSource = PGSimpleDataSource().apply {
            setURL(config.jdbcUrl)
            user = config.username
            password = config.password
            sslMode = config.sslMode
            currentSchema = config.schema
            connectTimeout = config.connectTimeoutSeconds
            applicationName = "nexora-flyway-migrator"
        }

        return Flyway.configure()
            .dataSource(dataSource)
            .schemas(config.schema)
            .defaultSchema(config.schema)
            .createSchemas(false)
            .table(HISTORY_TABLE)
            .locations(*config.locations.toTypedArray())
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .validateOnMigrate(true)
            .load()
    }

    fun migrate(): MigrationSummary {
        val result: MigrateResult = buildFlyway().migrate()
        return MigrationSummary(
            schema = config.schema,
            migrationsExecuted = result.migrationsExecuted,
            targetSchemaVersion = result.targetSchemaVersion ?: result.initialSchemaVersion,
        )
    }

    fun validate(): ValidateResult = buildFlyway().validateWithResult()

    fun info(): List<MigrationInfo> = buildFlyway().info().all().toList()

    companion object {
        const val HISTORY_TABLE = "flyway_schema_history"
    }
}
