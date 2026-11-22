package com.nexora.backend.bootstrap

import com.nexora.backend.adapter.persistence.postgres.FlywayConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayMigrator
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.nexora.backend.bootstrap.MigrateMain")

fun main(args: Array<String>) {
    val command = args.firstOrNull()?.trim()?.lowercase() ?: "migrate"
    require(command in setOf("migrate", "validate", "info")) {
        "Unsupported migration command '$command'. Allowed commands: migrate, validate, info"
    }

    val config = FlywayConfig.fromEnv()
    logger.info("Executing Flyway command '{}' with {}", command, config)

    val migrator = FlywayMigrator(config)
    when (command) {
        "migrate" -> {
            val summary = migrator.migrate()
            logger.info(
                "Flyway migrate completed [schema={}, executed={}, targetVersion={}]",
                summary.schema,
                summary.migrationsExecuted,
                summary.targetSchemaVersion,
            )
        }
        "validate" -> {
            val result = migrator.validate()
            if (!result.validationSuccessful) {
                logger.error("Flyway validation failed [invalidMigrations={}]", result.invalidMigrations.size)
                throw IllegalStateException("Flyway validation failed")
            }
            logger.info("Flyway validation succeeded [validated={}]", result.validateCount)
        }
        "info" -> {
            val entries = migrator.info()
            entries.forEach { info ->
                logger.info(
                    "Migration [version={}, description={}, state={}, checksum={}]",
                    info.version?.version ?: "-",
                    info.description,
                    info.state.displayName,
                    info.checksum ?: "-",
                )
            }
        }
    }
}
