package com.nexora.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.adapter.persistence.postgres.DatabaseConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayMigrator
import com.nexora.backend.adapter.persistence.postgres.JdbcTransactionManager
import com.nexora.backend.adapter.persistence.postgres.PostgresRuntimePool
import com.nexora.backend.application.health.HealthService
import com.nexora.backend.application.health.HealthStatus
import com.nexora.backend.application.persistence.MissingIdentityContextException
import com.nexora.backend.bootstrap.AppConfig
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.postgresql.util.PSQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real PostgreSQL 17 integration tests (T07 / T08 infrastructure scope, Phase 1C).
 *
 * Opt-in via `-Dnexora.test.localPostgres=true` with passwords injected ONLY through
 * environment variables (`NEXORA_RUNTIME_PASSWORD` and `FLYWAY_PASSWORD` or `NEXORA_MIGRATION_PASSWORD`).
 * Never connects to remote Supabase and never logs passwords.
 */
class PostgresInfrastructureIntegrationTest {
    private val jdbcUrl = System.getProperty(
        "nexora.test.localPostgresUrl",
        System.getenv("NEXORA_RUNTIME_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:55422/postgres",
    )

    private fun requireEnabled() {
        val enabled = System.getProperty("nexora.test.localPostgres", "false").equals("true", ignoreCase = true)
        Assume.assumeTrue("Set -Dnexora.test.localPostgres=true to run real PostgreSQL integration tests", enabled)
        require(jdbcUrl.startsWith("jdbc:postgresql://127.0.0.1:") || jdbcUrl.startsWith("jdbc:postgresql://localhost:")) {
            "Integration tests must run exclusively against local loopback PostgreSQL, got '$jdbcUrl'"
        }
    }

    private fun runtimePassword(): String =
        System.getenv("NEXORA_RUNTIME_PASSWORD")?.takeIf { it.isNotEmpty() }
            ?: error("NEXORA_RUNTIME_PASSWORD environment variable is required when localPostgres tests are enabled")

    private fun migratorPassword(): String =
        (System.getenv("FLYWAY_PASSWORD") ?: System.getenv("NEXORA_MIGRATION_PASSWORD"))?.takeIf { it.isNotEmpty() }
            ?: error("FLYWAY_PASSWORD or NEXORA_MIGRATION_PASSWORD environment variable is required")

    private fun singleConnectionRuntimeConfig(): DatabaseConfig = DatabaseConfig(
        jdbcUrl = jdbcUrl,
        username = "app_runtime",
        password = runtimePassword(),
        schema = "nexora",
        sslMode = "prefer",
        maxPoolSize = 1,
        minIdle = 1,
        connectionTimeoutMs = 5_000,
    )

    private fun principalFor(userId: UUID): AuthenticatedPrincipal = AuthenticatedPrincipal(
        userId = userId,
        issuer = "http://127.0.0.1:55421",
        audience = "authenticated",
        sessionId = UUID.randomUUID(),
        aal = "aal1",
        issuedAt = Instant.now(),
        expiresAt = Instant.now().plusSeconds(3600),
    )

    @Test
    fun `Flyway migrates and validates schema nexora with app_migrator without creating business tables`() {
        requireEnabled()
        val flywayConfig = FlywayConfig(
            jdbcUrl = jdbcUrl,
            username = "app_migrator",
            password = migratorPassword(),
            schema = "nexora",
            sslMode = "prefer",
        )
        val migrator = FlywayMigrator(flywayConfig)

        val firstRun = migrator.migrate()
        assertEquals("nexora", firstRun.schema)
        assertTrue((firstRun.targetSchemaVersion?.toIntOrNull() ?: 0) >= 1)

        // Second run must be idempotent (0 new migrations)
        val secondRun = migrator.migrate()
        assertEquals(0, secondRun.migrationsExecuted)
        assertEquals(firstRun.targetSchemaVersion, secondRun.targetSchemaVersion)

        // Validation must pass
        val validation = migrator.validate()
        assertTrue(validation.validationSuccessful, "Flyway validation must succeed")

        // Verify effective migrator role attributes and absence of Phase 2 business tables
        val props = Properties().apply {
            setProperty("user", "app_migrator")
            setProperty("password", migratorPassword())
            setProperty("sslmode", "prefer")
        }
        DriverManager.getConnection(jdbcUrl, props).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT current_user,
                           r.rolsuper,
                           r.rolbypassrls,
                           r.rolcreaterole,
                           r.rolcreatedb,
                           r.rolinherit,
                           has_schema_privilege('app_migrator', 'nexora', 'CREATE'),
                           has_column_privilege('app_migrator', 'auth.users', 'id', 'REFERENCES'),
                           (SELECT count(*) FROM pg_auth_members WHERE member = r.oid)
                    FROM pg_roles r
                    WHERE r.rolname = current_user
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("app_migrator", rs.getString(1))
                    assertFalse(rs.getBoolean(2), "app_migrator must not be SUPERUSER")
                    assertFalse(rs.getBoolean(3), "app_migrator must not have BYPASSRLS")
                    assertFalse(rs.getBoolean(4), "app_migrator must not have CREATEROLE")
                    assertFalse(rs.getBoolean(5), "app_migrator must not have CREATEDB")
                    assertFalse(rs.getBoolean(6), "app_migrator must not have INHERIT")
                    assertTrue(rs.getBoolean(7), "app_migrator must have CREATE on schema nexora")
                    assertTrue(rs.getBoolean(8), "app_migrator must have REFERENCES on auth.users(id)")
                    assertEquals(0, rs.getInt(9), "app_migrator must have no role memberships")
                }

                // Confirm Flyway history exists in nexora and no Phase 3+ tables exist
                stmt.executeQuery(
                    """
                    SELECT tablename
                    FROM pg_tables
                    WHERE schemaname = 'nexora'
                    ORDER BY tablename
                    """.trimIndent(),
                ).use { rs ->
                    val tables = buildSet {
                        while (rs.next()) add(rs.getString(1))
                    }
                    assertTrue("flyway_schema_history" in tables)
                    assertFalse("courses" in tables)
                    assertFalse("enrollments" in tables)
                }
            }
        }
    }

    @Test
    fun `rejects bad credentials, fails cleanly when Postgres is unreachable, and closes pool properly`() {
        requireEnabled()

        // Bad password for app_runtime fails fast
        val wrongPasswordConfig = DatabaseConfig(
            jdbcUrl = jdbcUrl,
            username = "app_runtime",
            password = "wrong-password-${UUID.randomUUID()}",
            schema = "nexora",
            sslMode = "prefer",
            maxPoolSize = 1,
            minIdle = 0,
            connectionTimeoutMs = 2_000,
        )
        assertFailsWith<Exception> {
            PostgresRuntimePool.create(wrongPasswordConfig, failFast = true).close()
        }

        // Unreachable host/port fails fast
        val unreachableConfig = DatabaseConfig(
            jdbcUrl = "jdbc:postgresql://127.0.0.1:1/postgres",
            username = "app_runtime",
            password = runtimePassword(),
            schema = "nexora",
            sslMode = "prefer",
            maxPoolSize = 1,
            minIdle = 0,
            connectionTimeoutMs = 1_000,
        )
        assertFailsWith<Exception> {
            PostgresRuntimePool.create(unreachableConfig, failFast = true).close()
        }

        // Valid pool connects, reports ready in Ktor /health/ready, and degrades cleanly when closed
        val pool = PostgresRuntimePool.create(singleConnectionRuntimeConfig(), failFast = true)
        val txManager = JdbcTransactionManager(pool)
        val dbCheck = pool.readinessCheck(txManager)

        testApplication {
            val config = AppConfig.fromEnv(mapOf("NEXORA_ENV" to "test"))
            val healthService = HealthService(
                serviceName = config.serviceName,
                environment = config.environment.wireName,
                readinessChecks = mapOf("database" to dbCheck),
            )
            application {
                nexoraSaasModule(config, healthService)
            }

            val okResp = client.get("/health/ready")
            assertEquals(HttpStatusCode.OK, okResp.status)
            val okJson = ObjectMapper().readTree(okResp.bodyAsText())
            assertEquals("ready", okJson["status"].asText())
            assertEquals("ok", okJson["checks"]["database"].asText())

            pool.close()
            assertTrue(pool.isClosed)
            assertEquals(HealthStatus.NOT_READY, runBlocking { dbCheck.check().status })

            val closedResp = client.get("/health/ready")
            assertEquals(HttpStatusCode.ServiceUnavailable, closedResp.status)
            val closedJson = ObjectMapper().readTree(closedResp.bodyAsText())
            assertEquals("not_ready", closedJson["checks"]["database"].asText())
        }
    }

    @Test
    fun `app_runtime has least privilege and cannot create tables, temp tables, or read auth_users`() = runBlocking {
        requireEnabled()
        PostgresRuntimePool.create(singleConnectionRuntimeConfig(), failFast = true).use { pool ->
            val txManager = JdbcTransactionManager(pool)

            txManager.inInfrastructureTransaction(readOnly = true) { session ->
                session.connection.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """
                        SELECT current_user,
                               session_user,
                               r.rolsuper,
                               r.rolbypassrls,
                               r.rolcreaterole,
                               r.rolcreatedb,
                               r.rolinherit,
                               (SELECT count(*) FROM pg_auth_members WHERE member = r.oid),
                               (SELECT count(*) FROM pg_class WHERE relowner = r.oid)
                        FROM pg_roles r
                        WHERE r.rolname = current_user
                        """.trimIndent(),
                    ).use { rs ->
                        assertTrue(rs.next())
                        assertEquals("app_runtime", rs.getString(1))
                        assertEquals("app_runtime", rs.getString(2))
                        assertFalse(rs.getBoolean(3), "app_runtime must not be SUPERUSER")
                        assertFalse(rs.getBoolean(4), "app_runtime must not have BYPASSRLS")
                        assertFalse(rs.getBoolean(5), "app_runtime must not have CREATEROLE")
                        assertFalse(rs.getBoolean(6), "app_runtime must not have CREATEDB")
                        assertFalse(rs.getBoolean(7), "app_runtime must not have INHERIT")
                        assertEquals(0, rs.getInt(8), "app_runtime must have no role memberships")
                        assertEquals(0, rs.getInt(9), "app_runtime must own zero relations")
                    }
                }
            }

            // Cannot CREATE TABLE in nexora
            val createTableErr = assertFailsWith<PSQLException> {
                txManager.inInfrastructureTransaction(readOnly = false) { session ->
                    session.connection.createStatement().use {
                        it.execute("CREATE TABLE nexora.runtime_ddl_denied (id int)")
                    }
                }
            }
            assertEquals("42501", createTableErr.sqlState)

            // Cannot CREATE TEMP TABLE
            val createTempErr = assertFailsWith<PSQLException> {
                txManager.inInfrastructureTransaction(readOnly = false) { session ->
                    session.connection.createStatement().use {
                        it.execute("CREATE TEMP TABLE runtime_temp_denied (id int)")
                    }
                }
            }
            assertEquals("42501", createTempErr.sqlState)

            // Cannot read auth.users
            val authUsersErr = assertFailsWith<PSQLException> {
                txManager.inInfrastructureTransaction(readOnly = true) { session ->
                    session.connection.createStatement().use {
                        it.executeQuery("SELECT id FROM auth.users LIMIT 1").close()
                    }
                }
            }
            assertEquals("42501", authUsersErr.sqlState)
        }
    }

    @Test
    fun `T07 and T08 transactional identity context isolates users A and B on reused physical connection`() =
        runBlocking {
            requireEnabled()
            // Ensure V1 migration (nexora.current_user_id()) is present
            FlywayMigrator(
                FlywayConfig(
                    jdbcUrl = jdbcUrl,
                    username = "app_migrator",
                    password = migratorPassword(),
                    schema = "nexora",
                    sslMode = "prefer",
                ),
            ).migrate()

            val userA = principalFor(UUID.randomUUID())
            val userB = principalFor(UUID.randomUUID())

            PostgresRuntimePool.create(singleConnectionRuntimeConfig(), failFast = true).use { pool ->
                val txManager = JdbcTransactionManager(pool)

                fun queryContext(conn: java.sql.Connection): Triple<Int, String?, String?> {
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery(
                            "SELECT pg_backend_pid(), current_setting('app.user_id', true), nexora.current_user_id()::text",
                        ).use { rs ->
                            assertTrue(rs.next())
                            return Triple(rs.getInt(1), rs.getString(2), rs.getString(3))
                        }
                    }
                }

                // 1. Authenticated transaction for User A (commit)
                val (pidA, rawA, helperA) = txManager.inAuthenticatedTransaction(userA) { session ->
                    queryContext(session.connection)
                }
                assertEquals(userA.userId.toString(), rawA)
                assertEquals(userA.userId.toString(), helperA)

                // 2. Direct borrow of the SAME physical connection after commit: ZERO inherited identity
                pool.dataSource.connection.use { rawConn ->
                    val (pidAfterCommit, rawAfterCommit, helperAfterCommit) = queryContext(rawConn)
                    rawConn.rollback()
                    assertEquals(pidA, pidAfterCommit, "Pool of size 1 must reuse the exact same physical backend PID")
                    assertTrue(rawAfterCommit.isNullOrEmpty(), "app.user_id must be empty after commit, got '$rawAfterCommit'")
                    assertNull(helperAfterCommit, "nexora.current_user_id() must be NULL after commit")
                }

                // 3. Authenticated transaction for User B that throws and forces ROLLBACK
                var pidB = -1
                assertFailsWith<IllegalStateException> {
                    txManager.inAuthenticatedTransaction(userB) { session ->
                        val (pid, rawB, helperB) = queryContext(session.connection)
                        pidB = pid
                        assertEquals(pidA, pidB, "User B must reuse the same physical connection")
                        assertEquals(userB.userId.toString(), rawB)
                        assertEquals(userB.userId.toString(), helperB)
                        throw IllegalStateException("Simulated business error forcing rollback")
                    }
                }

                // 4. Direct borrow immediately after rollback/exception: ZERO contamination
                pool.dataSource.connection.use { rawConn ->
                    val (pidAfterRollback, rawAfterRollback, helperAfterRollback) = queryContext(rawConn)
                    rawConn.rollback()
                    assertEquals(pidA, pidAfterRollback)
                    assertTrue(
                        rawAfterRollback.isNullOrEmpty(),
                        "app.user_id must be empty after rollback, got '$rawAfterRollback'",
                    )
                    assertNull(helperAfterRollback, "nexora.current_user_id() must be NULL after rollback")
                }

                // 5. Infrastructure transaction has NULL identity and read-only transaction mode
                val (pidInfra, rawInfra, helperInfra) = txManager.inInfrastructureTransaction(readOnly = true) { session ->
                    val readOnlyFlag = session.connection.createStatement().use { stmt ->
                        stmt.executeQuery("SHOW transaction_read_only").use { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    }
                    assertEquals("on", readOnlyFlag)
                    queryContext(session.connection)
                }
                assertEquals(pidA, pidInfra)
                assertTrue(rawInfra.isNullOrEmpty())
                assertNull(helperInfra)

                // 6. Consecutive alternating operations A -> B -> A -> infra -> B on same physical connection
                val sequence = listOf(userA, userB, userA, null, userB)
                for (actor in sequence) {
                    val (pid, _, helperUid) = if (actor != null) {
                        txManager.inAuthenticatedTransaction(actor) { queryContext(it.connection) }
                    } else {
                        txManager.inInfrastructureTransaction { queryContext(it.connection) }
                    }
                    assertEquals(pidA, pid)
                    assertEquals(actor?.userId?.toString(), helperUid)
                }

                // 7. Missing mandatory identity fails closed with MissingIdentityContextException
                assertFailsWith<MissingIdentityContextException> {
                    txManager.requireAuthenticatedTransaction(null) { "must-not-run" }
                }

                // 8. Malformed app.user_id fails cleanly with PostgreSQL 22P02 inside nexora.current_user_id()
                val malformedErr = assertFailsWith<SQLException> {
                    txManager.inInfrastructureTransaction(readOnly = true) { session ->
                        session.connection.prepareStatement("SELECT set_config('app.user_id', ?, true)").use { stmt ->
                            stmt.setString(1, "not-a-valid-uuid")
                            stmt.execute()
                        }
                        session.connection.createStatement().use { stmt ->
                            stmt.executeQuery("SELECT nexora.current_user_id()").close()
                        }
                    }
                }
                assertEquals("22P02", malformedErr.sqlState)
            }
        }
}
