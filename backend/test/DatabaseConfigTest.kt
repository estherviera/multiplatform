package com.nexora.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.adapter.persistence.postgres.DatabaseConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayConfig
import com.nexora.backend.application.persistence.MissingIdentityContextException
import com.nexora.backend.application.persistence.TransactionManager
import com.nexora.backend.application.persistence.TransactionalSession
import com.nexora.backend.bootstrap.AppConfig
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseConfigTest {
    @Test
    fun `database is disabled by default when no runtime variables are set and when NEXORA_DB_ENABLED is false`() {
        val defaultConfig = AppConfig.fromEnv(emptyMap())
        assertNull(defaultConfig.database)
        assertTrue(defaultConfig.toString().contains("databaseConfigured=false"))

        val explicitlyDisabled = AppConfig.fromEnv(
            mapOf(
                "NEXORA_DB_ENABLED" to "false",
                "NEXORA_RUNTIME_JDBC_URL" to "jdbc:postgresql://127.0.0.1:55422/postgres",
                "NEXORA_RUNTIME_USER" to "app_runtime",
                "NEXORA_RUNTIME_PASSWORD" to "secret-local-password-value",
            ),
        )
        assertNull(explicitlyDisabled.database)
    }

    @Test
    fun `parses valid runtime database configuration, redacts password, and ignores FLYWAY variables`() {
        val secretPassword = "very-secret-runtime-password-123456"
        val flywaySecret = "very-secret-migrator-password-999999"
        val config = AppConfig.fromEnv(
            mapOf(
                "NEXORA_ENV" to "local",
                "NEXORA_DB_ENABLED" to "true",
                "NEXORA_RUNTIME_JDBC_URL" to "jdbc:postgresql://127.0.0.1:55422/postgres",
                "NEXORA_RUNTIME_USER" to "app_runtime",
                "NEXORA_RUNTIME_PASSWORD" to secretPassword,
                "NEXORA_DB_MAX_POOL_SIZE" to "4",
                "NEXORA_DB_MIN_IDLE" to "1",
                "NEXORA_DB_PREPARE_THRESHOLD" to "0",
                "FLYWAY_URL" to "jdbc:postgresql://127.0.0.1:55422/postgres",
                "FLYWAY_USER" to "app_migrator",
                "FLYWAY_PASSWORD" to flywaySecret,
            ),
        )

        val db = assertNotNull(config.database)
        assertEquals("app_runtime", db.username)
        assertEquals("nexora", db.schema)
        assertEquals("prefer", db.sslMode)
        assertEquals(4, db.maxPoolSize)
        assertEquals(1, db.minIdle)
        assertEquals(0, db.prepareThreshold)
        assertFalse(db.toString().contains(secretPassword))
        assertFalse(config.toString().contains(secretPassword))
        assertFalse(config.toString().contains(flywaySecret))
        assertTrue(config.toString().contains("databaseConfigured=true"))
    }

    @Test
    fun `fails closed on missing fields, embedded passwords, disallowed runtime roles, and weak TLS in production`() {
        // Missing password when enabled
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_DB_ENABLED" to "true",
                    "NEXORA_RUNTIME_JDBC_URL" to "jdbc:postgresql://127.0.0.1:55422/postgres",
                    "NEXORA_RUNTIME_USER" to "app_runtime",
                ),
            )
        }

        // Embedded password in URL query
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig(
                jdbcUrl = "jdbc:postgresql://127.0.0.1:55422/postgres?password=leaked",
                username = "app_runtime",
                password = "secret",
            )
        }

        // Embedded userinfo in URL
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig(
                jdbcUrl = "jdbc:postgresql://app_runtime:leaked@127.0.0.1:55422/postgres",
                username = "app_runtime",
                password = "secret",
            )
        }

        // Disallowed privileged or migrator roles for runtime
        for (badUser in listOf("postgres", "supabase_admin", "service_role", "app_migrator")) {
            assertFailsWith<IllegalArgumentException> {
                DatabaseConfig(
                    jdbcUrl = "jdbc:postgresql://127.0.0.1:55422/postgres",
                    username = badUser,
                    password = "secret",
                )
            }
        }

        // Weak SSL mode rejected in production
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "production",
                    "NEXORA_READINESS_TOKEN" to "ops-token",
                    "NEXORA_AUTH_ISSUER" to "https://example.supabase.co/auth/v1",
                    "NEXORA_AUTH_JWKS_URL" to "https://example.supabase.co/auth/v1/.well-known/jwks.json",
                    "NEXORA_DB_ENABLED" to "true",
                    "NEXORA_RUNTIME_JDBC_URL" to "jdbc:postgresql://db.example.supabase.co:5432/postgres",
                    "NEXORA_RUNTIME_USER" to "app_runtime",
                    "NEXORA_RUNTIME_PASSWORD" to "secret",
                    "NEXORA_RUNTIME_SSL_MODE" to "prefer",
                ),
            )
        }
    }

    @Test
    fun `validates FlywayConfig role separation and protects internal schemas`() {
        val secret = "migrator-secret-password-999"
        val valid = FlywayConfig.fromEnv(
            mapOf(
                "FLYWAY_URL" to "jdbc:postgresql://127.0.0.1:55422/postgres",
                "FLYWAY_USER" to "app_migrator",
                "FLYWAY_PASSWORD" to secret,
            ),
        )
        assertEquals("app_migrator", valid.username)
        assertEquals("nexora", valid.schema)
        assertFalse(valid.toString().contains(secret))

        // Rejects app_runtime and admin roles
        for (badRole in listOf("app_runtime", "postgres", "supabase_admin", "service_role")) {
            assertFailsWith<IllegalArgumentException> {
                FlywayConfig(
                    jdbcUrl = "jdbc:postgresql://127.0.0.1:55422/postgres",
                    username = badRole,
                    password = secret,
                )
            }
        }

        // Rejects internal auth schema
        assertFailsWith<IllegalArgumentException> {
            FlywayConfig(
                jdbcUrl = "jdbc:postgresql://127.0.0.1:55422/postgres",
                username = "app_migrator",
                password = secret,
                schema = "auth",
            )
        }
    }

    @Test
    fun `requireAuthenticatedTransaction fails closed before acquiring connection and maps to HTTP 401`() =
        testApplication {
            var connectionAcquired = false
            val fakeTxManager = object : TransactionManager {
                override suspend fun <T> inAuthenticatedTransaction(
                    principal: AuthenticatedPrincipal,
                    readOnly: Boolean,
                    block: (TransactionalSession) -> T,
                ): T {
                    connectionAcquired = true
                    error("Should never be reached without principal")
                }

                override suspend fun <T> inInfrastructureTransaction(
                    readOnly: Boolean,
                    block: (TransactionalSession) -> T,
                ): T {
                    connectionAcquired = true
                    error("Should never be reached")
                }
            }

            runBlocking {
                assertFailsWith<MissingIdentityContextException> {
                    fakeTxManager.requireAuthenticatedTransaction(null) { "never" }
                }
            }
            assertFalse(connectionAcquired, "No database connection may be acquired when principal is missing")

            val config = AppConfig.fromEnv(mapOf("NEXORA_ENV" to "test"))
            application {
                nexoraSaasModule(config) {
                    get("/_test/missing-identity-tx") {
                        fakeTxManager.requireAuthenticatedTransaction(null) { Unit }
                    }
                }
            }

            val resp = client.get("/_test/missing-identity-tx")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            val err = ObjectMapper().readTree(resp.bodyAsText())["error"]
            assertEquals("missing_identity_context", err["code"].asText())
            assertFalse(connectionAcquired)
        }
}
