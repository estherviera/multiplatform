package com.nexora.backend

import com.nexora.backend.adapter.persistence.postgres.DatabaseConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayMigrator
import com.nexora.backend.adapter.persistence.postgres.JdbcTransactionManager
import com.nexora.backend.adapter.persistence.postgres.PostgresRuntimePool
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import java.sql.Connection
import java.sql.DriverManager
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
import kotlin.test.assertTrue

/**
 * Real PostgreSQL 17 integration tests for Phase 2A multitenant schema:
 * - Flyway V2 applied cleanly on top of untouched V1.
 * - Tables, PKs, FKs (ON DELETE RESTRICT), CHECKs, UNIQUE(slug), and indexes.
 * - RLS enabled with default-deny (0 permissive policies) and zero grants to app_runtime/PUBLIC.
 * - Deferred active-admin continuity trigger verified in rolled-back transactions (no leftover data).
 *
 * Opt-in via `-Dnexora.test.localPostgres=true` with passwords injected ONLY via environment variables.
 */
class MultitenantSchemaIntegrationTest {
    private val jdbcUrl = System.getProperty(
        "nexora.test.localPostgresUrl",
        System.getenv("NEXORA_RUNTIME_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:55422/postgres",
    )

    private fun requireEnabled() {
        val enabled = System.getProperty("nexora.test.localPostgres", "false").equals("true", ignoreCase = true)
        Assume.assumeTrue("Set -Dnexora.test.localPostgres=true to run Phase 2A schema integration tests", enabled)
        require(jdbcUrl.startsWith("jdbc:postgresql://127.0.0.1:") || jdbcUrl.startsWith("jdbc:postgresql://localhost:")) {
            "Integration tests must run exclusively against local loopback PostgreSQL, got '$jdbcUrl'"
        }
    }

    private fun runtimePassword(): String =
        System.getenv("NEXORA_RUNTIME_PASSWORD")?.takeIf { it.isNotEmpty() }
            ?: error("NEXORA_RUNTIME_PASSWORD environment variable is required")

    private fun migratorPassword(): String =
        (System.getenv("FLYWAY_PASSWORD") ?: System.getenv("NEXORA_MIGRATION_PASSWORD"))?.takeIf { it.isNotEmpty() }
            ?: error("FLYWAY_PASSWORD or NEXORA_MIGRATION_PASSWORD environment variable is required")

    private fun openMigratorConnection(): Connection {
        val props = Properties().apply {
            setProperty("user", "app_migrator")
            setProperty("password", migratorPassword())
            setProperty("sslmode", "prefer")
        }
        return DriverManager.getConnection(jdbcUrl, props)
    }

    private fun runFlyway(): FlywayMigrator {
        val migrator = FlywayMigrator(
            FlywayConfig(
                jdbcUrl = jdbcUrl,
                username = "app_migrator",
                password = migratorPassword(),
                schema = "nexora",
                sslMode = "prefer",
            ),
        )
        migrator.migrate()
        return migrator
    }

    @Test
    fun `V2 creates core multitenant tables, constraints, indexes, and enables default-deny RLS`() {
        requireEnabled()
        val migrator = runFlyway()

        val validation = migrator.validate()
        assertTrue(validation.validationSuccessful, "Flyway validation must succeed across V1 and V2")
        val versions = migrator.info().mapNotNull { it.version?.version }
        assertEquals(listOf("1", "2"), versions)

        openMigratorConnection().use { conn ->
            conn.createStatement().use { stmt ->
                // 1. Exact tables in schema nexora (no courses, enrollments, payments, or communities)
                stmt.executeQuery(
                    """
                    SELECT tablename
                    FROM pg_tables
                    WHERE schemaname = 'nexora'
                    ORDER BY tablename
                    """.trimIndent(),
                ).use { rs ->
                    val tables = buildList { while (rs.next()) add(rs.getString(1)) }
                    assertEquals(
                        listOf("companies", "company_memberships", "flyway_schema_history", "profiles"),
                        tables,
                    )
                }

                // 2. RLS enabled, owner = app_migrator, zero permissive policies (default deny)
                stmt.executeQuery(
                    """
                    SELECT c.relname,
                           c.relrowsecurity,
                           pg_get_userbyid(c.relowner) AS owner,
                           (SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'nexora' AND p.tablename = c.relname) AS policy_count
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'nexora'
                      AND c.relname IN ('profiles', 'companies', 'company_memberships')
                    ORDER BY c.relname
                    """.trimIndent(),
                ).use { rs ->
                    val checked = mutableListOf<String>()
                    while (rs.next()) {
                        val table = rs.getString(1)
                        checked.add(table)
                        assertTrue(rs.getBoolean(2), "RLS must be enabled on nexora.$table")
                        assertEquals("app_migrator", rs.getString(3), "Owner of nexora.$table must be app_migrator")
                        assertEquals(0, rs.getInt(4), "Phase 2A must have 0 permissive policies on nexora.$table")
                    }
                    assertEquals(listOf("companies", "company_memberships", "profiles"), checked)
                }

                // 3. Zero table privileges granted to PUBLIC or app_runtime in Phase 2A
                stmt.executeQuery(
                    """
                    SELECT c.relname,
                           has_table_privilege('app_runtime', c.oid, 'SELECT'),
                           has_table_privilege('app_runtime', c.oid, 'INSERT'),
                           has_table_privilege('app_runtime', c.oid, 'UPDATE'),
                           has_table_privilege('app_runtime', c.oid, 'DELETE'),
                           has_table_privilege('public', c.oid, 'SELECT')
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'nexora'
                      AND c.relname IN ('profiles', 'companies', 'company_memberships')
                    ORDER BY c.relname
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) {
                        val table = rs.getString(1)
                        assertFalse(rs.getBoolean(2), "app_runtime must not have SELECT on nexora.$table in 2A")
                        assertFalse(rs.getBoolean(3), "app_runtime must not have INSERT on nexora.$table in 2A")
                        assertFalse(rs.getBoolean(4), "app_runtime must not have UPDATE on nexora.$table in 2A")
                        assertFalse(rs.getBoolean(5), "app_runtime must not have DELETE on nexora.$table in 2A")
                        assertFalse(rs.getBoolean(6), "PUBLIC must not have SELECT on nexora.$table")
                    }
                }

                // 4. Verify expected indexes exist
                stmt.executeQuery(
                    """
                    SELECT indexname
                    FROM pg_indexes
                    WHERE schemaname = 'nexora'
                      AND tablename IN ('profiles', 'companies', 'company_memberships')
                    ORDER BY indexname
                    """.trimIndent(),
                ).use { rs ->
                    val indexes = buildSet { while (rs.next()) add(rs.getString(1)) }
                    assertTrue("companies_slug_unique" in indexes)
                    assertTrue("idx_companies_status" in indexes)
                    assertTrue("company_memberships_pkey" in indexes)
                    assertTrue("idx_company_memberships_user_status" in indexes)
                    assertTrue("idx_company_memberships_company_active_admins" in indexes)
                    assertTrue("idx_company_memberships_company_role_status" in indexes)
                }

                // 5. Verify all foreign keys use ON DELETE RESTRICT ('r') and profiles references auth.users(id)
                stmt.executeQuery(
                    """
                    SELECT conname, confdeltype::text, pg_get_constraintdef(oid)
                    FROM pg_constraint
                    WHERE connamespace = 'nexora'::regnamespace
                      AND contype = 'f'
                    ORDER BY conname
                    """.trimIndent(),
                ).use { rs ->
                    val fks = mutableListOf<Triple<String, String, String>>()
                    while (rs.next()) {
                        fks.add(Triple(rs.getString(1), rs.getString(2), rs.getString(3)))
                    }
                    assertEquals(3, fks.size, "Expected 3 FKs across profiles and company_memberships")
                    for ((name, delType, _) in fks) {
                        assertEquals("r", delType, "FK $name must use ON DELETE RESTRICT")
                    }
                    assertTrue(
                        fks.any { it.third.contains("REFERENCES auth.users(id)") },
                        "profiles.id must reference auth.users(id)",
                    )
                }
            }
        }
    }

    @Test
    fun `app_runtime cannot read, write, or disable RLS on multitenant tables`() = runBlocking {
        requireEnabled()
        runFlyway()

        val pool = PostgresRuntimePool.create(
            DatabaseConfig(
                jdbcUrl = jdbcUrl,
                username = "app_runtime",
                password = runtimePassword(),
                schema = "nexora",
                sslMode = "prefer",
                maxPoolSize = 1,
                minIdle = 1,
            ),
            failFast = true,
        )

        pool.use { runtimePool ->
            val txManager = JdbcTransactionManager(runtimePool)
            val actor = AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                issuer = "http://127.0.0.1:55421",
                audience = "authenticated",
                sessionId = UUID.randomUUID(),
                aal = "aal1",
                issuedAt = Instant.now(),
                expiresAt = Instant.now().plusSeconds(3600),
            )

            val forbiddenStatements = listOf(
                "SELECT * FROM nexora.profiles",
                "SELECT * FROM nexora.companies",
                "SELECT * FROM nexora.company_memberships",
                "INSERT INTO nexora.companies (slug, name) VALUES ('acme', 'Acme')",
                "DELETE FROM nexora.company_memberships",
                "ALTER TABLE nexora.companies DISABLE ROW LEVEL SECURITY",
            )

            for (sql in forbiddenStatements) {
                val err = assertFailsWith<PSQLException>("Expected 42501 for: $sql") {
                    txManager.inAuthenticatedTransaction(actor, readOnly = false) { session ->
                        session.connection.createStatement().use { it.execute(sql) }
                    }
                }
                assertEquals("42501", err.sqlState, "Expected insufficient_privilege (42501) for: $sql")
            }
        }
    }

    @Test
    fun `enforces domain constraints, multi-company roles, and active admin continuity trigger`() {
        requireEnabled()
        runFlyway()

        openMigratorConnection().use { conn ->
            conn.autoCommit = false
            try {
                // Verify invalid slug and invalid status fail CHECK constraints (23514)
                val spCheck = conn.setSavepoint("sp_check")
                val badSlugErr = assertFailsWith<PSQLException> {
                    conn.createStatement().use {
                        it.execute("INSERT INTO nexora.companies (slug, name) VALUES ('Invalid_Slug', 'Acme')")
                    }
                }
                assertEquals("23514", badSlugErr.sqlState)
                conn.rollback(spCheck)

                val spStatus = conn.setSavepoint("sp_status")
                val badStatusErr = assertFailsWith<PSQLException> {
                    conn.createStatement().use {
                        it.execute("INSERT INTO nexora.companies (slug, name, status) VALUES ('valid-slug', 'Acme', 'deleted')")
                    }
                }
                assertEquals("23514", badStatusErr.sqlState)
                conn.rollback(spStatus)

                // Verify profiles.id FK to auth.users(id) rejects unknown UUID (23503)
                val spFk = conn.setSavepoint("sp_fk")
                val missingAuthUserErr = assertFailsWith<PSQLException> {
                    conn.prepareStatement("INSERT INTO nexora.profiles (id, display_name) VALUES (?::uuid, 'Ghost')").use {
                        it.setString(1, UUID.randomUUID().toString())
                        it.executeUpdate()
                    }
                }
                assertEquals("23503", missingAuthUserErr.sqlState)
                conn.rollback(spFk)

                // Verify active company without an active admin fails when deferred constraints are checked (23514)
                val spNoAdmin = conn.setSavepoint("sp_no_admin")
                conn.createStatement().use {
                    it.execute("INSERT INTO nexora.companies (slug, name, status) VALUES ('orphan-co', 'Orphan Co', 'active')")
                }
                val orphanErr = assertFailsWith<PSQLException> {
                    conn.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
                }
                assertEquals("23514", orphanErr.sqlState)
                conn.rollback(spNoAdmin)

                // Verify suspended company without admins is allowed until reactivated
                val spSuspended = conn.setSavepoint("sp_suspended")
                conn.createStatement().use {
                    it.execute("INSERT INTO nexora.companies (slug, name, status) VALUES ('paused-co', 'Paused Co', 'suspended')")
                    it.execute("SET CONSTRAINTS ALL IMMEDIATE")
                }
                // Attempting to reactivate without an active admin fails (23514)
                val reactivateErr = assertFailsWith<PSQLException> {
                    conn.createStatement().use {
                        it.execute("UPDATE nexora.companies SET status = 'active' WHERE slug = 'paused-co'")
                        it.execute("SET CONSTRAINTS ALL IMMEDIATE")
                    }
                }
                assertEquals("23514", reactivateErr.sqlState)
                conn.rollback(spSuspended)
            } finally {
                // Roll back the entire test transaction so zero synthetic rows remain
                conn.rollback()
            }
        }
    }

    @Test
    fun `allows multiple companies per user with distinct roles and blocks demoting the last active admin`() {
        requireEnabled()
        runFlyway()

        // Ensure two real synthetic users exist in local Supabase Auth (if local Auth is running)
        // or skip only the auth.users-backed continuity portion if no local auth.users rows exist.
        openMigratorConnection().use { conn ->
            conn.autoCommit = false
            try {
                // Query existing auth.users IDs is disallowed for app_migrator (only REFERENCES granted, NOT SELECT!).
                // Instead, verify via local Supabase Auth if reachable so we test with real auth.users UUIDs.
                val authBaseUrl = System.getProperty("nexora.test.localAuthUrl", "http://127.0.0.1:55421").trimEnd('/')
                val http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2))
                    .build()
                val authHealthy = runCatching {
                    http.send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI("$authBaseUrl/health")).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding(),
                    ).statusCode() == 200
                }.getOrDefault(false)
                Assume.assumeTrue("Local Supabase Auth must be running on $authBaseUrl for real auth.users FK continuity test", authHealthy)

                val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                fun createLocalAuthUser(): UUID {
                    val email = "nexora-2a-${UUID.randomUUID()}@example.test"
                    val randomBytes = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
                    val password = "Nx!" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
                    val body = mapper.writeValueAsString(mapOf("email" to email, "password" to password))
                    val resp = http.send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI("$authBaseUrl/signup"))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .header("Content-Type", "application/json")
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString(),
                    )
                    assertEquals(200, resp.statusCode(), "Local GoTrue signup should succeed")
                    return UUID.fromString(mapper.readTree(resp.body())["user"]["id"].asText())
                }

                val user1 = createLocalAuthUser()
                val user2 = createLocalAuthUser()
                val companyA = UUID.randomUUID()
                val companyB = UUID.randomUUID()

                // Insert both profiles + both active companies + memberships inside one transaction
                conn.prepareStatement("INSERT INTO nexora.profiles (id, display_name) VALUES (?::uuid, ?)").use {
                    it.setString(1, user1.toString())
                    it.setString(2, "Admin One")
                    it.executeUpdate()
                    it.setString(1, user2.toString())
                    it.setString(2, "Admin Two")
                    it.executeUpdate()
                }

                conn.prepareStatement("INSERT INTO nexora.companies (id, slug, name, status) VALUES (?::uuid, ?, ?, 'active')").use {
                    it.setString(1, companyA.toString())
                    it.setString(2, "company-a-${companyA.toString().take(8)}")
                    it.setString(3, "Company A")
                    it.executeUpdate()
                    it.setString(1, companyB.toString())
                    it.setString(2, "company-b-${companyB.toString().take(8)}")
                    it.setString(3, "Company B")
                    it.executeUpdate()
                }

                // user1 is company_admin in A and member in B; user2 is company_admin in B
                conn.prepareStatement(
                    "INSERT INTO nexora.company_memberships (company_id, user_id, role, status) VALUES (?::uuid, ?::uuid, ?, 'active')",
                ).use {
                    it.setString(1, companyA.toString())
                    it.setString(2, user1.toString())
                    it.setString(3, "company_admin")
                    it.executeUpdate()

                    it.setString(1, companyB.toString())
                    it.setString(2, user1.toString())
                    it.setString(3, "member")
                    it.executeUpdate()

                    it.setString(1, companyB.toString())
                    it.setString(2, user2.toString())
                    it.setString(3, "company_admin")
                    it.executeUpdate()
                }

                // Constraints pass when both active companies have an active company_admin
                conn.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }

                // Duplicate (company_id, user_id) fails PK uniqueness (23505)
                val spDup = conn.setSavepoint("sp_dup")
                val dupErr = assertFailsWith<PSQLException> {
                    conn.prepareStatement(
                        "INSERT INTO nexora.company_memberships (company_id, user_id, role, status) VALUES (?::uuid, ?::uuid, 'trainer', 'active')",
                    ).use {
                        it.setString(1, companyA.toString())
                        it.setString(2, user1.toString())
                        it.executeUpdate()
                    }
                }
                assertEquals("23505", dupErr.sqlState)
                conn.rollback(spDup)

                // Demoting the SOLE active admin of Company A fails (23514)
                val spDemote = conn.setSavepoint("sp_demote")
                val demoteErr = assertFailsWith<PSQLException> {
                    conn.prepareStatement(
                        "UPDATE nexora.company_memberships SET role = 'member' WHERE company_id = ?::uuid AND user_id = ?::uuid",
                    ).use {
                        it.setString(1, companyA.toString())
                        it.setString(2, user1.toString())
                        it.executeUpdate()
                    }
                    conn.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
                }
                assertEquals("23514", demoteErr.sqlState)
                conn.rollback(spDemote)

                // Suspending the SOLE active admin of Company A fails (23514)
                val spSuspendAdmin = conn.setSavepoint("sp_suspend_admin")
                val suspendErr = assertFailsWith<PSQLException> {
                    conn.prepareStatement(
                        "UPDATE nexora.company_memberships SET status = 'suspended' WHERE company_id = ?::uuid AND user_id = ?::uuid",
                    ).use {
                        it.setString(1, companyA.toString())
                        it.setString(2, user1.toString())
                        it.executeUpdate()
                    }
                    conn.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
                }
                assertEquals("23514", suspendErr.sqlState)
                conn.rollback(spSuspendAdmin)

                // Deleting the SOLE active admin of Company A fails (23514)
                val spDeleteAdmin = conn.setSavepoint("sp_delete_admin")
                val deleteErr = assertFailsWith<PSQLException> {
                    conn.prepareStatement(
                        "DELETE FROM nexora.company_memberships WHERE company_id = ?::uuid AND user_id = ?::uuid",
                    ).use {
                        it.setString(1, companyA.toString())
                        it.setString(2, user1.toString())
                        it.executeUpdate()
                    }
                    conn.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
                }
                assertEquals("23514", deleteErr.sqlState)
                conn.rollback(spDeleteAdmin)

                // Adding user2 as active company_admin in Company A allows demoting user1 atomically
                conn.prepareStatement(
                    "INSERT INTO nexora.company_memberships (company_id, user_id, role, status) VALUES (?::uuid, ?::uuid, 'company_admin', 'active')",
                ).use {
                    it.setString(1, companyA.toString())
                    it.setString(2, user2.toString())
                    it.executeUpdate()
                }
                conn.prepareStatement(
                    "UPDATE nexora.company_memberships SET role = 'trainer' WHERE company_id = ?::uuid AND user_id = ?::uuid",
                ).use {
                    it.setString(1, companyA.toString())
                    it.setString(2, user1.toString())
                    it.executeUpdate()
                }
                conn.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
            } finally {
                // Roll back so no synthetic profiles/companies/memberships remain persisted
                conn.rollback()
            }
        }
    }
}
