package com.nexora.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.persistence.postgres.DatabaseConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayMigrator
import com.nexora.backend.adapter.persistence.postgres.JdbcTransactionManager
import com.nexora.backend.adapter.persistence.postgres.PostgresRuntimePool
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.postgresql.util.PSQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real PostgreSQL 17 integration tests for Phase 2B multitenant RLS and authorization:
 * - T03: Symmetric read isolation A/B, multi-company user, outsider, invited, suspended, suspended company, no identity.
 * - T04/T05/T06: Write isolation, anti-self-elevation, GRANT vs RLS enforcement, no company creation by app_runtime.
 * - T08: Physical connection reuse (HikariCP maxPoolSize=1) across real tenant rows.
 * - Active-admin continuity under app_runtime + real RLS: demotion/suspension denial, atomic replacement, concurrent race.
 * - Zero direct access for PUBLIC, anon, and authenticated roles.
 *
 * Opt-in via `-Dnexora.test.localPostgres=true` with passwords injected exclusively via environment variables.
 * Seeds fixtures via app_migrator and cleans up only its own synthetic UUIDs in finally.
 */
class MultitenantRlsIntegrationTest {
    private val jdbcUrl = System.getProperty(
        "nexora.test.localPostgresUrl",
        System.getenv("NEXORA_RUNTIME_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:55422/postgres",
    )
    private val authBaseUrl = System.getProperty("nexora.test.localAuthUrl", "http://127.0.0.1:55421").trimEnd('/')
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    private fun requireEnabled() {
        val enabled = System.getProperty("nexora.test.localPostgres", "false").equals("true", ignoreCase = true)
        Assume.assumeTrue("Set -Dnexora.test.localPostgres=true to run Phase 2B RLS integration tests", enabled)
        require(jdbcUrl.startsWith("jdbc:postgresql://127.0.0.1:") || jdbcUrl.startsWith("jdbc:postgresql://localhost:")) {
            "Integration tests must run exclusively against local loopback PostgreSQL, got '$jdbcUrl'"
        }
        val authHealthy = runCatching {
            http.send(
                HttpRequest.newBuilder(URI("$authBaseUrl/health")).GET().timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode() == 200
        }.getOrDefault(false)
        Assume.assumeTrue("Local Supabase Auth must be healthy at $authBaseUrl for real auth.users fixtures", authHealthy)
    }

    private fun runtimePassword(): String =
        System.getenv("NEXORA_RUNTIME_PASSWORD")?.takeIf { it.isNotEmpty() }
            ?: error("NEXORA_RUNTIME_PASSWORD environment variable is required")

    private fun migratorPassword(): String =
        (System.getenv("FLYWAY_PASSWORD") ?: System.getenv("NEXORA_MIGRATION_PASSWORD"))?.takeIf { it.isNotEmpty() }
            ?: error("FLYWAY_PASSWORD or NEXORA_MIGRATION_PASSWORD environment variable is required")

    private fun runFlyway() {
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
        assertTrue(migrator.validate().validationSuccessful)
    }

    private fun openMigratorConnection(): Connection {
        val props = Properties().apply {
            setProperty("user", "app_migrator")
            setProperty("password", migratorPassword())
            setProperty("sslmode", "prefer")
        }
        return DriverManager.getConnection(jdbcUrl, props)
    }

    private fun runtimePool(maxPoolSize: Int = 2): PostgresRuntimePool = PostgresRuntimePool.create(
        DatabaseConfig(
            jdbcUrl = jdbcUrl,
            username = "app_runtime",
            password = runtimePassword(),
            schema = "nexora",
            sslMode = "prefer",
            maxPoolSize = maxPoolSize,
            minIdle = 1,
        ),
        failFast = true,
    )

    private fun createLocalAuthUser(): UUID {
        val email = "nexora-2b-${UUID.randomUUID()}@example.test"
        val randomBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val password = "Nx!" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        val body = mapper.writeValueAsString(mapOf("email" to email, "password" to password))
        val resp = http.send(
            HttpRequest.newBuilder(URI("$authBaseUrl/signup"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, resp.statusCode(), "Local GoTrue signup should succeed")
        return UUID.fromString(mapper.readTree(resp.body())["user"]["id"].asText())
    }

    private fun principalFor(userId: UUID): AuthenticatedPrincipal = AuthenticatedPrincipal(
        userId = userId,
        issuer = authBaseUrl,
        audience = "authenticated",
        sessionId = UUID.randomUUID(),
        aal = "aal1",
        issuedAt = Instant.now(),
        expiresAt = Instant.now().plusSeconds(3600),
    )

    private data class TenantFixture(
        val companyA: UUID,
        val companyB: UUID,
        val companySuspended: UUID,
        val adminA: AuthenticatedPrincipal,
        val trainerA: AuthenticatedPrincipal,
        val memberA: AuthenticatedPrincipal,
        val invitedA: AuthenticatedPrincipal,
        val suspendedA: AuthenticatedPrincipal,
        val adminB: AuthenticatedPrincipal,
        val dualUser: AuthenticatedPrincipal,
        val outsider: AuthenticatedPrincipal,
        val adminSuspendedCo: AuthenticatedPrincipal,
        val secondCandidate: AuthenticatedPrincipal,
    ) {
        val allCompanies: List<UUID> = listOf(companyA, companyB, companySuspended)
        val allUsers: List<UUID> = listOf(
            adminA, trainerA, memberA, invitedA, suspendedA,
            adminB, dualUser, outsider, adminSuspendedCo, secondCandidate,
        ).map { it.userId }
    }

    private fun withFixture(block: (TenantFixture) -> Unit) {
        val fixture = TenantFixture(
            companyA = UUID.randomUUID(),
            companyB = UUID.randomUUID(),
            companySuspended = UUID.randomUUID(),
            adminA = principalFor(createLocalAuthUser()),
            trainerA = principalFor(createLocalAuthUser()),
            memberA = principalFor(createLocalAuthUser()),
            invitedA = principalFor(createLocalAuthUser()),
            suspendedA = principalFor(createLocalAuthUser()),
            adminB = principalFor(createLocalAuthUser()),
            dualUser = principalFor(createLocalAuthUser()),
            outsider = principalFor(createLocalAuthUser()),
            adminSuspendedCo = principalFor(createLocalAuthUser()),
            secondCandidate = principalFor(createLocalAuthUser()),
        )

        openMigratorConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("INSERT INTO nexora.profiles (id, display_name) VALUES (?::uuid, ?)").use { ps ->
                    for ((idx, uid) in fixture.allUsers.withIndex()) {
                        ps.setString(1, uid.toString())
                        ps.setString(2, "User $idx")
                        ps.executeUpdate()
                    }
                }
                conn.prepareStatement("INSERT INTO nexora.companies (id, slug, name, status) VALUES (?::uuid, ?, ?, ?)").use { ps ->
                    fun addCo(id: UUID, prefix: String, name: String, status: String) {
                        ps.setString(1, id.toString())
                        ps.setString(2, "$prefix-${id.toString().take(8)}")
                        ps.setString(3, name)
                        ps.setString(4, status)
                        ps.executeUpdate()
                    }
                    addCo(fixture.companyA, "rls-a", "Company A", "active")
                    addCo(fixture.companyB, "rls-b", "Company B", "active")
                    addCo(fixture.companySuspended, "rls-s", "Company Suspended", "suspended")
                }
                conn.prepareStatement(
                    "INSERT INTO nexora.company_memberships (company_id, user_id, role, status) VALUES (?::uuid, ?::uuid, ?, ?)",
                ).use { ps ->
                    fun addM(co: UUID, p: AuthenticatedPrincipal, role: String, status: String) {
                        ps.setString(1, co.toString())
                        ps.setString(2, p.userId.toString())
                        ps.setString(3, role)
                        ps.setString(4, status)
                        ps.executeUpdate()
                    }
                    // Company A
                    addM(fixture.companyA, fixture.adminA, "company_admin", "active")
                    addM(fixture.companyA, fixture.trainerA, "trainer", "active")
                    addM(fixture.companyA, fixture.memberA, "member", "active")
                    addM(fixture.companyA, fixture.invitedA, "member", "invited")
                    addM(fixture.companyA, fixture.suspendedA, "member", "suspended")
                    addM(fixture.companyA, fixture.dualUser, "company_admin", "active")
                    // Company B
                    addM(fixture.companyB, fixture.adminB, "company_admin", "active")
                    addM(fixture.companyB, fixture.dualUser, "member", "active")
                    // Suspended company
                    addM(fixture.companySuspended, fixture.adminSuspendedCo, "company_admin", "active")
                }
                conn.commit()
            } catch (ex: Throwable) {
                conn.rollback()
                throw ex
            }
        }

        try {
            block(fixture)
        } finally {
            // Clean up ONLY our synthetic fixture rows (suspend active companies first so admin trigger allows removal)
            openMigratorConnection().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement("UPDATE nexora.companies SET status = 'suspended' WHERE id = ?::uuid").use { ps ->
                        for (co in fixture.allCompanies) {
                            ps.setString(1, co.toString())
                            ps.executeUpdate()
                        }
                    }
                    conn.prepareStatement("DELETE FROM nexora.company_memberships WHERE company_id = ?::uuid").use { ps ->
                        for (co in fixture.allCompanies) {
                            ps.setString(1, co.toString())
                            ps.executeUpdate()
                        }
                    }
                    conn.prepareStatement("DELETE FROM nexora.companies WHERE id = ?::uuid").use { ps ->
                        for (co in fixture.allCompanies) {
                            ps.setString(1, co.toString())
                            ps.executeUpdate()
                        }
                    }
                    conn.prepareStatement("DELETE FROM nexora.profiles WHERE id = ?::uuid").use { ps ->
                        for (uid in fixture.allUsers) {
                            ps.setString(1, uid.toString())
                            ps.executeUpdate()
                        }
                    }
                    conn.commit()
                } catch (_: Throwable) {
                    runCatching { conn.rollback() }
                }
            }
        }
    }

    @Test
    fun `T03 read isolation enforces symmetric A-B boundaries, role scoping, and denies inactive or anonymous access`() {
        requireEnabled()
        runFlyway()

        withFixture { f ->
            runtimePool(maxPoolSize = 1).use { pool ->
                val tx = JdbcTransactionManager(pool)

                fun visibleProfileIds(actor: AuthenticatedPrincipal?): Set<UUID> = runBlocking {
                    val query: (Connection) -> Set<UUID> = { conn ->
                        conn.createStatement().use { st ->
                            st.executeQuery("SELECT id FROM nexora.profiles").use { rs ->
                                buildSet { while (rs.next()) add(UUID.fromString(rs.getString(1))) }
                            }
                        }
                    }
                    if (actor != null) tx.inAuthenticatedTransaction(actor, readOnly = true) { query(it.connection) }
                    else tx.inInfrastructureTransaction(readOnly = true) { query(it.connection) }
                }

                fun visibleCompanyIds(actor: AuthenticatedPrincipal?): Set<UUID> = runBlocking {
                    val query: (Connection) -> Set<UUID> = { conn ->
                        conn.createStatement().use { st ->
                            st.executeQuery("SELECT id FROM nexora.companies").use { rs ->
                                buildSet { while (rs.next()) add(UUID.fromString(rs.getString(1))) }
                            }
                        }
                    }
                    if (actor != null) tx.inAuthenticatedTransaction(actor, readOnly = true) { query(it.connection) }
                    else tx.inInfrastructureTransaction(readOnly = true) { query(it.connection) }
                }

                fun visibleMemberships(actor: AuthenticatedPrincipal?): Set<Pair<UUID, UUID>> = runBlocking {
                    val query: (Connection) -> Set<Pair<UUID, UUID>> = { conn ->
                        conn.createStatement().use { st ->
                            st.executeQuery("SELECT company_id, user_id FROM nexora.company_memberships").use { rs ->
                                buildSet {
                                    while (rs.next()) {
                                        add(UUID.fromString(rs.getString(1)) to UUID.fromString(rs.getString(2)))
                                    }
                                }
                            }
                        }
                    }
                    if (actor != null) tx.inAuthenticatedTransaction(actor, readOnly = true) { query(it.connection) }
                    else tx.inInfrastructureTransaction(readOnly = true) { query(it.connection) }
                }

                // 1. No identity -> zero rows across all 3 tables
                assertEquals(emptySet(), visibleProfileIds(null))
                assertEquals(emptySet(), visibleCompanyIds(null))
                assertEquals(emptySet(), visibleMemberships(null))

                // 2. Admin A sees own profile, ONLY Company A, and ALL 6 memberships of Company A (0 from B)
                assertEquals(setOf(f.adminA.userId), visibleProfileIds(f.adminA))
                assertEquals(setOf(f.companyA), visibleCompanyIds(f.adminA))
                val adminAMembers = visibleMemberships(f.adminA)
                assertEquals(6, adminAMembers.size)
                assertTrue(adminAMembers.all { it.first == f.companyA })

                // 3. Admin B sees own profile, ONLY Company B, and ALL 2 memberships of Company B (0 from A)
                assertEquals(setOf(f.adminB.userId), visibleProfileIds(f.adminB))
                assertEquals(setOf(f.companyB), visibleCompanyIds(f.adminB))
                assertEquals(
                    setOf(f.companyB to f.adminB.userId, f.companyB to f.dualUser.userId),
                    visibleMemberships(f.adminB),
                )

                // 4. Trainer A and Member A see ONLY Company A and ONLY their own active membership
                assertEquals(setOf(f.companyA), visibleCompanyIds(f.trainerA))
                assertEquals(setOf(f.companyA to f.trainerA.userId), visibleMemberships(f.trainerA))
                assertEquals(setOf(f.companyA), visibleCompanyIds(f.memberA))
                assertEquals(setOf(f.companyA to f.memberA.userId), visibleMemberships(f.memberA))

                // 5. Dual user (company_admin in A, member in B) sees both companies, ALL of A's memberships,
                //    and ONLY own membership in B (admin privilege in A NEVER leaks to B)
                assertEquals(setOf(f.companyA, f.companyB), visibleCompanyIds(f.dualUser))
                val dualMembers = visibleMemberships(f.dualUser)
                assertEquals(6, dualMembers.count { it.first == f.companyA })
                assertEquals(setOf(f.companyB to f.dualUser.userId), dualMembers.filter { it.first == f.companyB }.toSet())

                // 6. Outsider, invited, suspended, and suspended-company user see ZERO companies and ZERO memberships
                for (restrictedActor in listOf(f.outsider, f.invitedA, f.suspendedA, f.adminSuspendedCo)) {
                    assertEquals(setOf(restrictedActor.userId), visibleProfileIds(restrictedActor))
                    assertEquals(emptySet(), visibleCompanyIds(restrictedActor))
                    assertEquals(emptySet(), visibleMemberships(restrictedActor))
                }
            }
        }
    }

    @Test
    fun `T04 T05 T06 write isolation prevents cross-tenant mutations, self-elevation, and unauthorized company creation`() {
        requireEnabled()
        runFlyway()

        withFixture { f ->
            runtimePool(maxPoolSize = 1).use { pool ->
                val tx = JdbcTransactionManager(pool)

                runBlocking {
                    // 1. Profile: own display_name update succeeds; other profile = 0 rows (RLS); id update & DELETE = 42501 (GRANT)
                    val ownProfileUpdated = tx.inAuthenticatedTransaction(f.memberA) { session ->
                        session.connection.prepareStatement(
                            "UPDATE nexora.profiles SET display_name = 'Member A Updated', updated_at = now() WHERE id = ?::uuid",
                        ).use { ps ->
                            ps.setString(1, f.memberA.userId.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(1, ownProfileUpdated)

                    val otherProfileUpdated = tx.inAuthenticatedTransaction(f.memberA) { session ->
                        session.connection.prepareStatement(
                            "UPDATE nexora.profiles SET display_name = 'Hacked' WHERE id = ?::uuid",
                        ).use { ps ->
                            ps.setString(1, f.adminB.userId.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(0, otherProfileUpdated, "Cross-user profile UPDATE must affect 0 rows via RLS")

                    val idTamperErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(f.memberA) { session ->
                            session.connection.prepareStatement("UPDATE nexora.profiles SET id = ?::uuid WHERE id = ?::uuid").use { ps ->
                                ps.setString(1, UUID.randomUUID().toString())
                                ps.setString(2, f.memberA.userId.toString())
                                ps.executeUpdate()
                            }
                        }
                    }
                    assertEquals("42501", idTamperErr.sqlState)

                    val profileDeleteErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(f.memberA) { session ->
                            session.connection.createStatement().use { it.executeUpdate("DELETE FROM nexora.profiles") }
                        }
                    }
                    assertEquals("42501", profileDeleteErr.sqlState)

                    // 2. Companies: app_runtime CANNOT INSERT (T06 GRANT 42501), CANNOT DELETE (GRANT 42501),
                    //    CANNOT modify slug/status (column GRANT 42501)
                    val createCompanyErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(f.adminA) { session ->
                            session.connection.createStatement().use {
                                it.executeUpdate("INSERT INTO nexora.companies (slug, name) VALUES ('rogue-co', 'Rogue')")
                            }
                        }
                    }
                    assertEquals("42501", createCompanyErr.sqlState)

                    val statusTamperErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(f.adminA) { session ->
                            session.connection.prepareStatement("UPDATE nexora.companies SET status = 'suspended' WHERE id = ?::uuid").use { ps ->
                                ps.setString(1, f.companyA.toString())
                                ps.executeUpdate()
                            }
                        }
                    }
                    assertEquals("42501", statusTamperErr.sqlState)

                    // Admin A CAN update Company A name; CANNOT update Company B (0 rows via RLS)
                    val renameA = tx.inAuthenticatedTransaction(f.adminA) { session ->
                        session.connection.prepareStatement("UPDATE nexora.companies SET name = 'Company A Renamed', updated_at = now() WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, f.companyA.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(1, renameA)

                    val adminARenameB = tx.inAuthenticatedTransaction(f.adminA) { session ->
                        session.connection.prepareStatement("UPDATE nexora.companies SET name = 'Hacked B' WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, f.companyB.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(0, adminARenameB)

                    // Dual user (admin in A, member in B) CAN rename A, CANNOT rename B (T05)
                    val dualRenameA = tx.inAuthenticatedTransaction(f.dualUser) { session ->
                        session.connection.prepareStatement("UPDATE nexora.companies SET name = 'Company A Dual' WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, f.companyA.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(1, dualRenameA)
                    val dualRenameB = tx.inAuthenticatedTransaction(f.dualUser) { session ->
                        session.connection.prepareStatement("UPDATE nexora.companies SET name = 'Dual Hack B' WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, f.companyB.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(0, dualRenameB)

                    // Trainer & member CANNOT rename Company A (0 rows via RLS)
                    for (nonAdmin in listOf(f.trainerA, f.memberA, f.invitedA, f.suspendedA, f.outsider)) {
                        val rows = tx.inAuthenticatedTransaction(nonAdmin) { session ->
                            session.connection.prepareStatement("UPDATE nexora.companies SET name = 'NoPerm' WHERE id = ?::uuid").use { ps ->
                                ps.setString(1, f.companyA.toString())
                                ps.executeUpdate()
                            }
                        }
                        assertEquals(0, rows)
                    }

                    // 3. Memberships: self-elevation and cross-tenant writes blocked
                    // Trainer/member/dualUser(in B) attempting UPDATE self -> company_admin = 0 rows via RLS USING
                    for ((actor, company) in listOf(
                        f.memberA to f.companyA,
                        f.trainerA to f.companyA,
                        f.dualUser to f.companyB,
                    )) {
                        val rows = tx.inAuthenticatedTransaction(actor) { session ->
                            session.connection.prepareStatement(
                                "UPDATE nexora.company_memberships SET role = 'company_admin' WHERE company_id = ?::uuid AND user_id = ?::uuid",
                            ).use { ps ->
                                ps.setString(1, company.toString())
                                ps.setString(2, actor.userId.toString())
                                ps.executeUpdate()
                            }
                        }
                        assertEquals(0, rows, "Non-admin must not self-elevate to company_admin")
                    }

                    // Outsider / invited / suspended / adminA(in B) attempting INSERT company_admin -> RLS WITH CHECK 42501
                    for ((actor, company) in listOf(
                        f.outsider to f.companyA,
                        f.invitedA to f.companyA,
                        f.suspendedA to f.companyA,
                        f.adminA to f.companyB,
                    )) {
                        val err = assertFailsWith<PSQLException> {
                            tx.inAuthenticatedTransaction(actor) { session ->
                                session.connection.prepareStatement(
                                    "INSERT INTO nexora.company_memberships (company_id, user_id, role, status) VALUES (?::uuid, ?::uuid, 'company_admin', 'active')",
                                ).use { ps ->
                                    ps.setString(1, company.toString())
                                    ps.setString(2, f.secondCandidate.userId.toString())
                                    ps.executeUpdate()
                                }
                            }
                        }
                        assertEquals("42501", err.sqlState)
                    }

                    // Transferring company_id or DELETE membership -> GRANT 42501
                    val transferErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(f.adminA) { session ->
                            session.connection.prepareStatement(
                                "UPDATE nexora.company_memberships SET company_id = ?::uuid WHERE company_id = ?::uuid AND user_id = ?::uuid",
                            ).use { ps ->
                                ps.setString(1, f.companyB.toString())
                                ps.setString(2, f.companyA.toString())
                                ps.setString(3, f.memberA.userId.toString())
                                ps.executeUpdate()
                            }
                        }
                    }
                    assertEquals("42501", transferErr.sqlState)

                    val deleteMemberErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(f.adminA) { session ->
                            session.connection.createStatement().use {
                                it.executeUpdate("DELETE FROM nexora.company_memberships")
                            }
                        }
                    }
                    assertEquals("42501", deleteMemberErr.sqlState)

                    // Positive control: Admin A promotes Member A to trainer in Company A
                    val promoted = tx.inAuthenticatedTransaction(f.adminA) { session ->
                        session.connection.prepareStatement(
                            "UPDATE nexora.company_memberships SET role = 'trainer', updated_at = now() WHERE company_id = ?::uuid AND user_id = ?::uuid",
                        ).use { ps ->
                            ps.setString(1, f.companyA.toString())
                            ps.setString(2, f.memberA.userId.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(1, promoted)
                }
            }
        }
    }

    @Test
    fun `T08 physical connection reuse with pool size 1 isolates real tenant rows across alternating users`() {
        requireEnabled()
        runFlyway()

        withFixture { f ->
            runtimePool(maxPoolSize = 1).use { pool ->
                val tx = JdbcTransactionManager(pool)
                val observedPids = mutableSetOf<Int>()

                runBlocking {
                    val sequence = listOf(
                        f.adminA to setOf(f.companyA),
                        f.adminB to setOf(f.companyB),
                        f.outsider to emptySet(),
                        null to emptySet(),
                        f.adminB to setOf(f.companyB),
                        f.adminA to setOf(f.companyA),
                    )
                    for ((actor, expectedCompanies) in sequence) {
                        val (pid, companies) = if (actor != null) {
                            tx.inAuthenticatedTransaction(actor, readOnly = true) { session ->
                                val pid = session.connection.createStatement().use { st ->
                                    st.executeQuery("SELECT pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) }
                                }
                                val ids = session.connection.createStatement().use { st ->
                                    st.executeQuery("SELECT id FROM nexora.companies").use { rs ->
                                        buildSet { while (rs.next()) add(UUID.fromString(rs.getString(1))) }
                                    }
                                }
                                pid to ids
                            }
                        } else {
                            tx.inInfrastructureTransaction(readOnly = true) { session ->
                                val pid = session.connection.createStatement().use { st ->
                                    st.executeQuery("SELECT pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) }
                                }
                                val ids = session.connection.createStatement().use { st ->
                                    st.executeQuery("SELECT id FROM nexora.companies").use { rs ->
                                        buildSet { while (rs.next()) add(UUID.fromString(rs.getString(1))) }
                                    }
                                }
                                pid to ids
                            }
                        }
                        observedPids.add(pid)
                        assertEquals(expectedCompanies, companies)
                    }
                }
                assertEquals(1, observedPids.size, "Pool of size 1 must reuse the exact same physical PostgreSQL backend PID")
            }
        }
    }

    @Test
    fun `last active admin trigger works under app_runtime with RLS for denial, atomic replacement, and concurrent race`() {
        requireEnabled()
        runFlyway()

        withFixture { f ->
            runtimePool(maxPoolSize = 3).use { pool ->
                val tx = JdbcTransactionManager(pool)

                runBlocking {
                    // Company B has ONLY adminB as active company_admin:
                    // 1. Demoting sole active admin under app_runtime + RLS MUST fail (23514)
                    val demoteSoleErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(f.adminB) { session ->
                            session.connection.prepareStatement(
                                "UPDATE nexora.company_memberships SET role = 'trainer' WHERE company_id = ?::uuid AND user_id = ?::uuid",
                            ).use { ps ->
                                ps.setString(1, f.companyB.toString())
                                ps.setString(2, f.adminB.userId.toString())
                                ps.executeUpdate()
                            }
                        }
                    }
                    assertEquals("23514", demoteSoleErr.sqlState)

                    // 2. Suspending sole active admin under app_runtime + RLS MUST fail (23514)
                    val suspendSoleErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(f.adminB) { session ->
                            session.connection.prepareStatement(
                                "UPDATE nexora.company_memberships SET status = 'suspended' WHERE company_id = ?::uuid AND user_id = ?::uuid",
                            ).use { ps ->
                                ps.setString(1, f.companyB.toString())
                                ps.setString(2, f.adminB.userId.toString())
                                ps.executeUpdate()
                            }
                        }
                    }
                    assertEquals("23514", suspendSoleErr.sqlState)

                    // 3. Atomic replacement in ONE transaction as app_runtime (adminB adds secondCandidate as admin, then demotes self)
                    tx.inAuthenticatedTransaction(f.adminB) { session ->
                        session.connection.prepareStatement(
                            "INSERT INTO nexora.company_memberships (company_id, user_id, role, status) VALUES (?::uuid, ?::uuid, 'company_admin', 'active')",
                        ).use { ps ->
                            ps.setString(1, f.companyB.toString())
                            ps.setString(2, f.secondCandidate.userId.toString())
                            ps.executeUpdate()
                        }
                        session.connection.prepareStatement(
                            "UPDATE nexora.company_memberships SET role = 'trainer' WHERE company_id = ?::uuid AND user_id = ?::uuid",
                        ).use { ps ->
                            ps.setString(1, f.companyB.toString())
                            ps.setString(2, f.adminB.userId.toString())
                            ps.executeUpdate()
                        }
                    }

                    // Verify adminB is now trainer (cannot rename Company B) and secondCandidate is active admin (can rename B)
                    val formerAdminRename = tx.inAuthenticatedTransaction(f.adminB) { session ->
                        session.connection.prepareStatement("UPDATE nexora.companies SET name = 'Blocked' WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, f.companyB.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(0, formerAdminRename)
                    val newAdminRename = tx.inAuthenticatedTransaction(f.secondCandidate) { session ->
                        session.connection.prepareStatement("UPDATE nexora.companies SET name = 'Company B Handed Off' WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, f.companyB.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(1, newAdminRename)
                }

                // 4. Concurrent demotion race on Company A (which has exactly 2 active admins: adminA and dualUser):
                //    Both attempt to demote themselves concurrently on two separate physical connections.
                //    FOR UPDATE serialization in enforce_company_has_active_admin() guarantees EXACTLY 1 succeeds
                //    and EXACTLY 1 fails with SQLSTATE 23514, preserving >= 1 active admin.
                val readyToCommit = CountDownLatch(2)
                val releaseCommit = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    fun submitSelfDemotion(actor: AuthenticatedPrincipal) = executor.submit<String> {
                        try {
                            runBlocking {
                                tx.inAuthenticatedTransaction(actor) { session ->
                                    session.connection.prepareStatement(
                                        "UPDATE nexora.company_memberships SET role = 'trainer' WHERE company_id = ?::uuid AND user_id = ?::uuid",
                                    ).use { ps ->
                                        ps.setString(1, f.companyA.toString())
                                        ps.setString(2, actor.userId.toString())
                                        assertEquals(1, ps.executeUpdate())
                                    }
                                    readyToCommit.countDown()
                                    assertTrue(releaseCommit.await(5, TimeUnit.SECONDS))
                                }
                            }
                            "COMMITTED"
                        } catch (ex: PSQLException) {
                            "PSQL:${ex.sqlState}"
                        }
                    }

                    val f1 = submitSelfDemotion(f.adminA)
                    val f2 = submitSelfDemotion(f.dualUser)
                    assertTrue(readyToCommit.await(5, TimeUnit.SECONDS), "Both concurrent transactions must reach commit barrier")
                    releaseCommit.countDown()

                    val outcomes = listOf(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS)).sorted()
                    assertEquals(listOf("COMMITTED", "PSQL:23514"), outcomes)
                } finally {
                    executor.shutdownNow()
                }

                // Confirm Company A still has exactly 1 active company_admin
                openMigratorConnection().use { conn ->
                    conn.prepareStatement(
                        "SELECT count(*) FROM nexora.company_memberships WHERE company_id = ?::uuid AND role = 'company_admin' AND status = 'active'",
                    ).use { ps ->
                        ps.setString(1, f.companyA.toString())
                        ps.executeQuery().use { rs ->
                            assertTrue(rs.next())
                            assertEquals(1, rs.getInt(1))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `PUBLIC, anon, and authenticated have zero access to nexora schema, tables, and security helpers`() {
        requireEnabled()
        runFlyway()

        openMigratorConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT r.rolname,
                           has_schema_privilege(r.rolname, 'nexora', 'USAGE') AS schema_usage,
                           has_table_privilege(r.rolname, 'nexora.profiles', 'SELECT') AS profiles_sel,
                           has_table_privilege(r.rolname, 'nexora.companies', 'SELECT') AS companies_sel,
                           has_table_privilege(r.rolname, 'nexora.company_memberships', 'SELECT') AS memberships_sel,
                           has_function_privilege(r.rolname, 'nexora.is_active_company_admin(uuid)', 'EXECUTE') AS admin_fn_exec,
                           has_function_privilege(r.rolname, 'nexora.is_active_company_member(uuid)', 'EXECUTE') AS member_fn_exec
                    FROM pg_roles r
                    WHERE r.rolname IN ('anon', 'authenticated')
                    ORDER BY r.rolname
                    """.trimIndent(),
                ).use { rs ->
                    val rolesChecked = mutableListOf<String>()
                    while (rs.next()) {
                        val role = rs.getString(1)
                        rolesChecked.add(role)
                        assertFalse(rs.getBoolean(2), "$role must not have USAGE on schema nexora")
                        assertFalse(rs.getBoolean(3), "$role must not have SELECT on nexora.profiles")
                        assertFalse(rs.getBoolean(4), "$role must not have SELECT on nexora.companies")
                        assertFalse(rs.getBoolean(5), "$role must not have SELECT on nexora.company_memberships")
                        assertFalse(rs.getBoolean(6), "$role must not have EXECUTE on is_active_company_admin")
                        assertFalse(rs.getBoolean(7), "$role must not have EXECUTE on is_active_company_member")
                    }
                    assertEquals(listOf("anon", "authenticated"), rolesChecked)
                }

                // PUBLIC also has zero access
                stmt.executeQuery(
                    """
                    SELECT has_schema_privilege('public', 'nexora', 'USAGE'),
                           has_table_privilege('public', 'nexora.profiles', 'SELECT'),
                           has_table_privilege('public', 'nexora.companies', 'SELECT'),
                           has_table_privilege('public', 'nexora.company_memberships', 'SELECT'),
                           has_function_privilege('public', 'nexora.is_active_company_admin(uuid)', 'EXECUTE'),
                           has_function_privilege('public', 'nexora.is_active_company_member(uuid)', 'EXECUTE')
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next())
                    for (col in 1..6) {
                        assertFalse(rs.getBoolean(col), "PUBLIC privilege column $col must be false")
                    }
                }
            }
        }
    }
}
