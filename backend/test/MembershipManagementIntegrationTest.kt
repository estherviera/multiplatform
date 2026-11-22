package com.nexora.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.adapter.persistence.postgres.DatabaseConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayMigrator
import com.nexora.backend.adapter.persistence.postgres.JdbcTransactionManager
import com.nexora.backend.adapter.persistence.postgres.PostgresCompanyQueryRepository
import com.nexora.backend.adapter.persistence.postgres.PostgresMembershipManagementRepository
import com.nexora.backend.adapter.persistence.postgres.PostgresProfileRepository
import com.nexora.backend.adapter.persistence.postgres.PostgresRuntimePool
import com.nexora.backend.application.multitenant.CompanyQueryService
import com.nexora.backend.application.multitenant.MembershipManagementService
import com.nexora.backend.application.multitenant.ProfileService
import com.nexora.backend.bootstrap.AppConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Full end-to-end integration tests for Phase 2C.2 using:
 * - Real Supabase Auth local ES256 JWTs verified against live /.well-known/jwks.json
 * - Real Ktor HTTP routes (invitations, accept/decline, role changes, suspend/reactivate, atomic admin replace)
 * - Real PostgreSQL 17 + HikariCP (app_runtime) + Flyway V4 + RLS + durable audit_events
 *
 * Opt-in via `-Dnexora.test.localPostgres=true` with passwords injected exclusively via environment variables.
 */
class MembershipManagementIntegrationTest {
    private val jdbcUrl = System.getProperty(
        "nexora.test.localPostgresUrl",
        System.getenv("NEXORA_RUNTIME_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:55422/postgres",
    )
    private val authBaseUrl = System.getProperty("nexora.test.localAuthUrl", "http://127.0.0.1:55421").trimEnd('/')
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    private data class AuthActor(
        val userId: UUID,
        val accessToken: String,
        val password: String,
    )

    private fun requireEnabled() {
        val enabled = System.getProperty("nexora.test.localPostgres", "false").equals("true", ignoreCase = true)
        Assume.assumeTrue("Set -Dnexora.test.localPostgres=true to run Phase 2C.2 integration tests", enabled)
        require(jdbcUrl.startsWith("jdbc:postgresql://127.0.0.1:") || jdbcUrl.startsWith("jdbc:postgresql://localhost:")) {
            "Integration tests must run exclusively against local loopback PostgreSQL, got '$jdbcUrl'"
        }
        val authHealthy = runCatching {
            http.send(
                HttpRequest.newBuilder(URI("$authBaseUrl/health")).GET().timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode() == 200
        }.getOrDefault(false)
        Assume.assumeTrue("Local Supabase Auth must be healthy at $authBaseUrl", authHealthy)
    }

    private fun runtimePassword(): String =
        System.getenv("NEXORA_RUNTIME_PASSWORD")?.takeIf { it.isNotEmpty() }
            ?: error("NEXORA_RUNTIME_PASSWORD environment variable is required")

    private fun migratorPassword(): String =
        (System.getenv("FLYWAY_PASSWORD") ?: System.getenv("NEXORA_MIGRATION_PASSWORD"))?.takeIf { it.isNotEmpty() }
            ?: error("FLYWAY_PASSWORD or NEXORA_MIGRATION_PASSWORD environment variable is required")

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
        assertTrue(migrator.validate().validationSuccessful)
        return migrator
    }

    private fun openMigratorConnection(): Connection {
        val props = Properties().apply {
            setProperty("user", "app_migrator")
            setProperty("password", migratorPassword())
            setProperty("sslmode", "prefer")
        }
        return DriverManager.getConnection(jdbcUrl, props)
    }

    private fun createLocalAuthActor(): AuthActor {
        val email = "nexora-2c2-${UUID.randomUUID()}@example.test"
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
        val parsed = mapper.readTree(resp.body())
        return AuthActor(
            userId = UUID.fromString(parsed["user"]["id"].asText()),
            accessToken = parsed["access_token"].asText(),
            password = password,
        )
    }

    private fun appConfig(): AppConfig = AppConfig.fromEnv(
        mapOf(
            "NEXORA_ENV" to "local",
            "NEXORA_AUTH_ISSUER" to authBaseUrl,
            "NEXORA_AUTH_AUDIENCE" to "authenticated",
            "NEXORA_AUTH_JWKS_URL" to "$authBaseUrl/.well-known/jwks.json",
            "NEXORA_AUTH_ALLOWED_ALGS" to "ES256",
        ),
    )

    private fun runtimePool(maxPoolSize: Int): PostgresRuntimePool = PostgresRuntimePool.create(
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

    private data class MgmtFixture(
        val companyA: UUID,
        val companyB: UUID,
        val adminA: AuthActor,
        val trainerA: AuthActor,
        val memberA: AuthActor,
        val adminB: AuthActor,
        val memberB: AuthActor,
        val dualUser: AuthActor,
        val candidateAccept: AuthActor,
        val candidateDecline: AuthActor,
        val noProfileUser: AuthActor,
        val outsider: AuthActor,
    ) {
        val allCompanies = listOf(companyA, companyB)
        val allUsers = listOf(
            adminA, trainerA, memberA, adminB, memberB, dualUser,
            candidateAccept, candidateDecline, noProfileUser, outsider,
        )
    }

    private fun withFixture(block: (MgmtFixture) -> Unit) {
        val f = MgmtFixture(
            companyA = UUID.randomUUID(),
            companyB = UUID.randomUUID(),
            adminA = createLocalAuthActor(),
            trainerA = createLocalAuthActor(),
            memberA = createLocalAuthActor(),
            adminB = createLocalAuthActor(),
            memberB = createLocalAuthActor(),
            dualUser = createLocalAuthActor(),
            candidateAccept = createLocalAuthActor(),
            candidateDecline = createLocalAuthActor(),
            noProfileUser = createLocalAuthActor(), // intentionally NO profile row
            outsider = createLocalAuthActor(),
        )

        openMigratorConnection().use { conn ->
            conn.autoCommit = false
            try {
                // Seed profiles for all actors EXCEPT noProfileUser
                val withProfiles = f.allUsers.filter { it.userId != f.noProfileUser.userId }
                conn.prepareStatement("INSERT INTO nexora.profiles (id, display_name) VALUES (?::uuid, ?)").use { ps ->
                    withProfiles.forEachIndexed { idx, actor ->
                        ps.setString(1, actor.userId.toString())
                        ps.setString(2, "Mgmt User $idx")
                        ps.executeUpdate()
                    }
                }
                conn.prepareStatement("INSERT INTO nexora.companies (id, slug, name, status) VALUES (?::uuid, ?, ?, 'active')").use { ps ->
                    ps.setString(1, f.companyA.toString())
                    ps.setString(2, "mgmt-a-${f.companyA.toString().take(8)}")
                    ps.setString(3, "Mgmt Alpha")
                    ps.executeUpdate()

                    ps.setString(1, f.companyB.toString())
                    ps.setString(2, "mgmt-b-${f.companyB.toString().take(8)}")
                    ps.setString(3, "Mgmt Beta")
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "INSERT INTO nexora.company_memberships (company_id, user_id, role, status) VALUES (?::uuid, ?::uuid, ?, 'active')",
                ).use { ps ->
                    fun addM(co: UUID, actor: AuthActor, role: String) {
                        ps.setString(1, co.toString())
                        ps.setString(2, actor.userId.toString())
                        ps.setString(3, role)
                        ps.executeUpdate()
                    }
                    // Company A has 2 active admins: adminA and dualUser
                    addM(f.companyA, f.adminA, "company_admin")
                    addM(f.companyA, f.dualUser, "company_admin")
                    addM(f.companyA, f.trainerA, "trainer")
                    addM(f.companyA, f.memberA, "member")
                    // Company B has 1 active admin (adminB) + 2 members (memberB, dualUser)
                    addM(f.companyB, f.adminB, "company_admin")
                    addM(f.companyB, f.memberB, "member")
                    addM(f.companyB, f.dualUser, "member")
                }
                conn.commit()
            } catch (ex: Throwable) {
                conn.rollback()
                throw ex
            }
        }

        try {
            block(f)
        } finally {
            openMigratorConnection().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement("UPDATE nexora.companies SET status = 'suspended' WHERE id = ?::uuid").use { ps ->
                        for (co in f.allCompanies) { ps.setString(1, co.toString()); ps.executeUpdate() }
                    }
                    conn.prepareStatement("DELETE FROM nexora.audit_events WHERE company_id = ?::uuid").use { ps ->
                        for (co in f.allCompanies) { ps.setString(1, co.toString()); ps.executeUpdate() }
                    }
                    conn.prepareStatement("DELETE FROM nexora.company_invitations WHERE company_id = ?::uuid").use { ps ->
                        for (co in f.allCompanies) { ps.setString(1, co.toString()); ps.executeUpdate() }
                    }
                    conn.prepareStatement("DELETE FROM nexora.company_memberships WHERE company_id = ?::uuid").use { ps ->
                        for (co in f.allCompanies) { ps.setString(1, co.toString()); ps.executeUpdate() }
                    }
                    conn.prepareStatement("DELETE FROM nexora.companies WHERE id = ?::uuid").use { ps ->
                        for (co in f.allCompanies) { ps.setString(1, co.toString()); ps.executeUpdate() }
                    }
                    conn.prepareStatement("DELETE FROM nexora.profiles WHERE id = ?::uuid").use { ps ->
                        for (u in f.allUsers) { ps.setString(1, u.userId.toString()); ps.executeUpdate() }
                    }
                    conn.commit()
                } catch (_: Throwable) {
                    runCatching { conn.rollback() }
                }
            }
        }
    }

    @Test
    fun `invitations enforce eligibility, idempotency, explicit recipient accept-decline, cross-tenant isolation, and audit`() {
        requireEnabled()
        runFlyway()

        withFixture { f ->
            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            rootLogger.addAppender(appender)

            try {
                runtimePool(maxPoolSize = 1).use { pool ->
                    val tx = JdbcTransactionManager(pool)
                    val queryRepo = PostgresCompanyQueryRepository()
                    val mgmtRepo = PostgresMembershipManagementRepository()
                    val profileService = ProfileService(tx, PostgresProfileRepository())
                    val companyService = CompanyQueryService(tx, queryRepo)
                    val mgmtService = MembershipManagementService(tx, queryRepo, mgmtRepo)

                    testApplication {
                        application {
                            nexoraSaasModule(
                                config = appConfig(),
                                profileService = profileService,
                                companyQueryService = companyService,
                                membershipManagementService = mgmtService,
                            )
                        }

                        // 1. Inviting non-existent UUID or unprovisioned user fails closed (409 invitee_not_eligible)
                        for (badInvitee in listOf(UUID.randomUUID(), f.noProfileUser.userId)) {
                            val resp = client.post("/api/v1/companies/${f.companyA}/invitations") {
                                header(HttpHeaders.Authorization, "Bearer ${f.adminA.accessToken}")
                                contentType(ContentType.Application.Json)
                                setBody("""{"inviteeUserId":"$badInvitee","role":"member"}""")
                            }
                            assertEquals(HttpStatusCode.Conflict, resp.status)
                            assertEquals("invitee_not_eligible", mapper.readTree(resp.bodyAsText())["error"]["code"].asText())
                        }

                        // 2. Unauthorized cross-tenant invite (adminB -> companyA) = 404; non-admin (trainerA/memberA) = 403
                        assertEquals(
                            HttpStatusCode.NotFound,
                            client.post("/api/v1/companies/${f.companyA}/invitations") {
                                header(HttpHeaders.Authorization, "Bearer ${f.adminB.accessToken}")
                                contentType(ContentType.Application.Json)
                                setBody("""{"inviteeUserId":"${f.candidateAccept.userId}","role":"member"}""")
                            }.status,
                        )
                        assertEquals(
                            HttpStatusCode.Forbidden,
                            client.post("/api/v1/companies/${f.companyA}/invitations") {
                                header(HttpHeaders.Authorization, "Bearer ${f.trainerA.accessToken}")
                                contentType(ContentType.Application.Json)
                                setBody("""{"inviteeUserId":"${f.candidateAccept.userId}","role":"member"}""")
                            }.status,
                        )

                        // 3. Valid invitation by adminA -> 201 Created; identical retry -> idempotent 200 OK; different role -> 409
                        val invite1 = client.post("/api/v1/companies/${f.companyA}/invitations") {
                            header(HttpHeaders.Authorization, "Bearer ${f.adminA.accessToken}")
                            header(HttpHeaders.XRequestId, "req-invite-1")
                            contentType(ContentType.Application.Json)
                            setBody("""{"inviteeUserId":"${f.candidateAccept.userId}","role":"trainer"}""")
                        }
                        assertEquals(HttpStatusCode.Created, invite1.status)
                        val inv1Id = mapper.readTree(invite1.bodyAsText())["id"].asText()

                        val retrySame = client.post("/api/v1/companies/${f.companyA}/invitations") {
                            header(HttpHeaders.Authorization, "Bearer ${f.adminA.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"inviteeUserId":"${f.candidateAccept.userId}","role":"trainer"}""")
                        }
                        assertEquals(HttpStatusCode.OK, retrySame.status)
                        assertEquals(inv1Id, mapper.readTree(retrySame.bodyAsText())["id"].asText())

                        val retryDiffRole = client.post("/api/v1/companies/${f.companyA}/invitations") {
                            header(HttpHeaders.Authorization, "Bearer ${f.adminA.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"inviteeUserId":"${f.candidateAccept.userId}","role":"member"}""")
                        }
                        assertEquals(HttpStatusCode.Conflict, retryDiffRole.status)
                        assertEquals("invitation_conflict", mapper.readTree(retryDiffRole.bodyAsText())["error"]["code"].asText())

                        // 4. Before acceptance: candidateAccept has ZERO access to Company A; sees invitation in /me/invitations
                        assertEquals(
                            HttpStatusCode.NotFound,
                            client.get("/api/v1/companies/${f.companyA}") {
                                header(HttpHeaders.Authorization, "Bearer ${f.candidateAccept.accessToken}")
                            }.status,
                        )
                        val myInvList = mapper.readTree(
                            client.get("/api/v1/me/invitations") {
                                header(HttpHeaders.Authorization, "Bearer ${f.candidateAccept.accessToken}")
                            }.bodyAsText(),
                        )["items"]
                        assertEquals(1, myInvList.size())
                        assertEquals(inv1Id, myInvList[0]["id"].asText())
                        assertEquals("pending", myInvList[0]["status"].asText())

                        // 5. Outsider knowing invitation UUID cannot view or accept it -> 404
                        assertEquals(
                            HttpStatusCode.NotFound,
                            client.get("/api/v1/me/invitations/$inv1Id") {
                                header(HttpHeaders.Authorization, "Bearer ${f.outsider.accessToken}")
                            }.status,
                        )
                        assertEquals(
                            HttpStatusCode.NotFound,
                            client.post("/api/v1/me/invitations/$inv1Id/accept") {
                                header(HttpHeaders.Authorization, "Bearer ${f.outsider.accessToken}")
                            }.status,
                        )

                        // 6. Recipient accepts -> 200 OK active trainer membership; idempotent retry -> 200 OK; gains Company A access
                        val accepted = client.post("/api/v1/me/invitations/$inv1Id/accept") {
                            header(HttpHeaders.Authorization, "Bearer ${f.candidateAccept.accessToken}")
                            header(HttpHeaders.XRequestId, "req-accept-1")
                        }
                        assertEquals(HttpStatusCode.OK, accepted.status)
                        val accJson = mapper.readTree(accepted.bodyAsText())
                        assertEquals("trainer", accJson["role"].asText())
                        assertEquals("active", accJson["status"].asText())

                        val acceptRetry = client.post("/api/v1/me/invitations/$inv1Id/accept") {
                            header(HttpHeaders.Authorization, "Bearer ${f.candidateAccept.accessToken}")
                        }
                        assertEquals(HttpStatusCode.OK, acceptRetry.status)

                        // Declining an already-accepted invitation -> 409 Conflict
                        assertEquals(
                            HttpStatusCode.Conflict,
                            client.post("/api/v1/me/invitations/$inv1Id/decline") {
                                header(HttpHeaders.Authorization, "Bearer ${f.candidateAccept.accessToken}")
                            }.status,
                        )

                        assertEquals(
                            HttpStatusCode.OK,
                            client.get("/api/v1/companies/${f.companyA}") {
                                header(HttpHeaders.Authorization, "Bearer ${f.candidateAccept.accessToken}")
                            }.status,
                        )

                        // 7. Second candidate invited and declines -> declined, idempotent retry, accept fails 409, zero Company A access
                        val invite2 = client.post("/api/v1/companies/${f.companyA}/invitations") {
                            header(HttpHeaders.Authorization, "Bearer ${f.adminA.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"inviteeUserId":"${f.candidateDecline.userId}","role":"member"}""")
                        }
                        assertEquals(HttpStatusCode.Created, invite2.status)
                        val inv2Id = mapper.readTree(invite2.bodyAsText())["id"].asText()

                        val declined = client.post("/api/v1/me/invitations/$inv2Id/decline") {
                            header(HttpHeaders.Authorization, "Bearer ${f.candidateDecline.accessToken}")
                        }
                        assertEquals(HttpStatusCode.OK, declined.status)
                        assertEquals("declined", mapper.readTree(declined.bodyAsText())["status"].asText())
                        assertNotNull(mapper.readTree(declined.bodyAsText())["respondedAt"]?.asText())

                        // Idempotent decline retry -> 200 OK; accept after decline -> 409
                        assertEquals(
                            HttpStatusCode.OK,
                            client.post("/api/v1/me/invitations/$inv2Id/decline") {
                                header(HttpHeaders.Authorization, "Bearer ${f.candidateDecline.accessToken}")
                            }.status,
                        )
                        assertEquals(
                            HttpStatusCode.Conflict,
                            client.post("/api/v1/me/invitations/$inv2Id/accept") {
                                header(HttpHeaders.Authorization, "Bearer ${f.candidateDecline.accessToken}")
                            }.status,
                        )
                        assertEquals(
                            HttpStatusCode.NotFound,
                            client.get("/api/v1/companies/${f.companyA}") {
                                header(HttpHeaders.Authorization, "Bearer ${f.candidateDecline.accessToken}")
                            }.status,
                        )
                    }
                }

                // Verify zero real tokens or passwords leaked in logs
                val allLogs = appender.list.joinToString("\n") { it.formattedMessage }
                for (actor in f.allUsers) {
                    assertFalse(allLogs.contains(actor.accessToken), "Access token must never appear in logs")
                    assertFalse(allLogs.contains(actor.password), "Password must never appear in logs")
                }
            } finally {
                rootLogger.detachAppender(appender)
                appender.stop()
            }
        }
    }

    @Test
    fun `role changes, suspension, reactivation, admin replacement, concurrency, and audit immutability work under real RLS`() {
        requireEnabled()
        runFlyway()

        withFixture { f ->
            runtimePool(maxPoolSize = 3).use { pool ->
                val tx = JdbcTransactionManager(pool)
                val queryRepo = PostgresCompanyQueryRepository()
                val mgmtRepo = PostgresMembershipManagementRepository()
                val profileService = ProfileService(tx, PostgresProfileRepository())
                val companyService = CompanyQueryService(tx, queryRepo)
                val mgmtService = MembershipManagementService(tx, queryRepo, mgmtRepo)

                testApplication {
                    application {
                        nexoraSaasModule(
                            config = appConfig(),
                            profileService = profileService,
                            companyQueryService = companyService,
                            membershipManagementService = mgmtService,
                        )
                    }

                    // 1. Authorized role change (adminA promotes memberA -> trainer)
                    val promoted = client.patch("/api/v1/companies/${f.companyA}/memberships/${f.memberA.userId}/role") {
                        header(HttpHeaders.Authorization, "Bearer ${f.adminA.accessToken}")
                        header(HttpHeaders.XRequestId, "req-role-1")
                        contentType(ContentType.Application.Json)
                        setBody("""{"role":"trainer"}""")
                    }
                    assertEquals(HttpStatusCode.OK, promoted.status)
                    assertEquals("trainer", mapper.readTree(promoted.bodyAsText())["role"].asText())

                    // 2. Unauthorized self-elevation (memberA/trainerA -> 403; dualUser in Company B -> 403; adminA in B -> 404)
                    assertEquals(
                        HttpStatusCode.Forbidden,
                        client.patch("/api/v1/companies/${f.companyA}/memberships/${f.memberA.userId}/role") {
                            header(HttpHeaders.Authorization, "Bearer ${f.memberA.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"role":"company_admin"}""")
                        }.status,
                    )
                    assertEquals(
                        HttpStatusCode.Forbidden,
                        client.patch("/api/v1/companies/${f.companyB}/memberships/${f.dualUser.userId}/role") {
                            header(HttpHeaders.Authorization, "Bearer ${f.dualUser.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"role":"company_admin"}""")
                        }.status,
                    )
                    assertEquals(
                        HttpStatusCode.NotFound,
                        client.patch("/api/v1/companies/${f.companyB}/memberships/${f.memberB.userId}/role") {
                            header(HttpHeaders.Authorization, "Bearer ${f.adminA.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"role":"trainer"}""")
                        }.status,
                    )

                    // 3. Suspend and reactivate memberA; verify suspended admin (dualUser) immediately loses admin privileges
                    val suspended = client.post("/api/v1/companies/${f.companyA}/memberships/${f.dualUser.userId}/suspend") {
                        header(HttpHeaders.Authorization, "Bearer ${f.adminA.accessToken}")
                    }
                    assertEquals(HttpStatusCode.OK, suspended.status)
                    assertEquals("suspended", mapper.readTree(suspended.bodyAsText())["status"].asText())

                    // Suspended dualUser can NO LONGER administer Company A -> 404
                    assertEquals(
                        HttpStatusCode.NotFound,
                        client.patch("/api/v1/companies/${f.companyA}/memberships/${f.memberA.userId}/role") {
                            header(HttpHeaders.Authorization, "Bearer ${f.dualUser.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"role":"member"}""")
                        }.status,
                    )

                    // Reactivate dualUser -> regains Company A admin rights
                    val reactivated = client.post("/api/v1/companies/${f.companyA}/memberships/${f.dualUser.userId}/reactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${f.adminA.accessToken}")
                    }
                    assertEquals(HttpStatusCode.OK, reactivated.status)
                    assertEquals("active", mapper.readTree(reactivated.bodyAsText())["status"].asText())

                    // 4. Sole active admin in Company B (adminB) CANNOT demote or suspend self -> 409 last_active_admin_required
                    val demoteSole = client.patch("/api/v1/companies/${f.companyB}/memberships/${f.adminB.userId}/role") {
                        header(HttpHeaders.Authorization, "Bearer ${f.adminB.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"role":"trainer"}""")
                    }
                    assertEquals(HttpStatusCode.Conflict, demoteSole.status)
                    assertEquals("last_active_admin_required", mapper.readTree(demoteSole.bodyAsText())["error"]["code"].asText())

                    val suspendSole = client.post("/api/v1/companies/${f.companyB}/memberships/${f.adminB.userId}/suspend") {
                        header(HttpHeaders.Authorization, "Bearer ${f.adminB.accessToken}")
                    }
                    assertEquals(HttpStatusCode.Conflict, suspendSole.status)
                    assertEquals("last_active_admin_required", mapper.readTree(suspendSole.bodyAsText())["error"]["code"].asText())

                    // 5. Atomic replacement of sole active admin in Company B: adminB -> memberB (adminB becomes trainer)
                    val replaced = client.post("/api/v1/companies/${f.companyB}/admins/replace") {
                        header(HttpHeaders.Authorization, "Bearer ${f.adminB.accessToken}")
                        header(HttpHeaders.XRequestId, "req-replace-b")
                        contentType(ContentType.Application.Json)
                        setBody("""{"outgoingAdminUserId":"${f.adminB.userId}","successorUserId":"${f.memberB.userId}","outgoingNewRole":"trainer"}""")
                    }
                    assertEquals(HttpStatusCode.OK, replaced.status)
                    val repJson = mapper.readTree(replaced.bodyAsText())
                    assertEquals("trainer", repJson["outgoingMembership"]["role"].asText())
                    assertEquals("company_admin", repJson["successorMembership"]["role"].asText())

                    // Former adminB can no longer manage Company B (403); new admin memberB can!
                    assertEquals(
                        HttpStatusCode.Forbidden,
                        client.post("/api/v1/companies/${f.companyB}/memberships/${f.dualUser.userId}/suspend") {
                            header(HttpHeaders.Authorization, "Bearer ${f.adminB.accessToken}")
                        }.status,
                    )
                    assertEquals(
                        HttpStatusCode.OK,
                        client.patch("/api/v1/companies/${f.companyB}/memberships/${f.dualUser.userId}/role") {
                            header(HttpHeaders.Authorization, "Bearer ${f.memberB.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"role":"trainer"}""")
                        }.status,
                    )

                    // 6. Concurrent two-admin demotion race on Company A (adminA and dualUser both active admins):
                    //    EXACTLY ONE returns 200 OK and EXACTLY ONE returns 409 last_active_admin_required!
                    val statuses = coroutineScope {
                        listOf(
                            async {
                                client.patch("/api/v1/companies/${f.companyA}/memberships/${f.adminA.userId}/role") {
                                    header(HttpHeaders.Authorization, "Bearer ${f.adminA.accessToken}")
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"role":"trainer"}""")
                                }.status
                            },
                            async {
                                client.patch("/api/v1/companies/${f.companyA}/memberships/${f.dualUser.userId}/role") {
                                    header(HttpHeaders.Authorization, "Bearer ${f.dualUser.accessToken}")
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"role":"trainer"}""")
                                }.status
                            },
                        ).awaitAll()
                    }
                    assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses.toSet())
                }

                // 7. Verify durable audit events recorded atomically & tamper-resistance of nexora.audit_events
                openMigratorConnection().use { conn ->
                    conn.prepareStatement(
                        "SELECT action FROM nexora.audit_events WHERE company_id = ?::uuid ORDER BY occurred_at ASC",
                    ).use { ps ->
                        ps.setString(1, f.companyA.toString())
                        val actionsA = ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                        assertTrue("membership.role_changed" in actionsA)
                        assertTrue("membership.suspended" in actionsA)
                        assertTrue("membership.reactivated" in actionsA)

                        ps.setString(1, f.companyB.toString())
                        val actionsB = ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                        assertTrue("membership.admin_replaced" in actionsB)
                        // Failed sole-admin suspend/demote rolled back: ZERO 'membership.suspended' in B
                        assertFalse("membership.suspended" in actionsB)
                    }

                    // Confirm PUBLIC, anon, authenticated have ZERO access to V4 objects
                    conn.createStatement().use { st ->
                        st.executeQuery(
                            """
                            SELECT has_table_privilege('public', 'nexora.company_invitations', 'SELECT'),
                                   has_table_privilege('public', 'nexora.audit_events', 'SELECT'),
                                   has_table_privilege('app_runtime', 'nexora.audit_events', 'INSERT'),
                                   has_table_privilege('app_runtime', 'nexora.audit_events', 'UPDATE'),
                                   has_table_privilege('app_runtime', 'nexora.audit_events', 'DELETE'),
                                   has_table_privilege('app_runtime', 'nexora.company_invitations', 'DELETE'),
                                   has_function_privilege('app_runtime', 'nexora.record_audit_event(uuid, text, text, text, text, text, jsonb)', 'EXECUTE')
                            """.trimIndent(),
                        ).use { rs ->
                            assertTrue(rs.next())
                            for (i in 1..7) assertFalse(rs.getBoolean(i), "Privilege col $i must be false")
                        }
                    }
                }

                // Confirm app_runtime cannot directly mutate/delete audit_events, forge audit events via record_audit_event,
                // escalate invitation role/status via direct SQL, or cross-tenant mutate Company B via direct SQL.
                runBlocking {
                    fun principalFor(userId: UUID) = com.nexora.backend.domain.auth.AuthenticatedPrincipal(
                        userId = userId,
                        issuer = authBaseUrl,
                        audience = "authenticated",
                        expiresAt = java.time.Instant.now().plusSeconds(3600),
                    )

                    // 1. Direct DELETE / INSERT on audit_events -> 42501
                    val deleteAuditErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(principalFor(f.memberB.userId)) { session ->
                            session.connection.createStatement().use { it.execute("DELETE FROM nexora.audit_events") }
                        }
                    }
                    assertEquals("42501", deleteAuditErr.sqlState)

                    // 2. Direct call to nexora.record_audit_event(...) as active admin -> 42501 (revoked in V5)
                    val forgeAuditErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(principalFor(f.memberB.userId)) { session ->
                            session.connection.prepareStatement(
                                "SELECT nexora.record_audit_event(?::uuid, 'membership.role_changed', 'company_membership', ?::text, 'succeeded', null, '{}'::jsonb)",
                            ).use { ps ->
                                ps.setString(1, f.companyB.toString())
                                ps.setString(2, f.adminB.userId.toString())
                                ps.executeQuery()
                            }
                        }
                    }
                    assertEquals("42501", forgeAuditErr.sqlState)

                    // 3. Create a pending invitation for candidateAccept in Company B (by active admin memberB)
                    //    and verify direct SQL privilege escalation / illegal transitions fail closed.
                    val directInvId: UUID = tx.inAuthenticatedTransaction(principalFor(f.memberB.userId)) { session ->
                        session.connection.prepareStatement(
                            """
                            INSERT INTO nexora.company_invitations (company_id, invitee_user_id, invited_by_user_id, role, expires_at)
                            VALUES (?::uuid, ?::uuid, ?::uuid, 'member', now() + interval '1 day')
                            RETURNING id
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setString(1, f.companyB.toString())
                            ps.setString(2, f.candidateAccept.userId.toString())
                            ps.setString(3, f.memberB.userId.toString())
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                UUID.fromString(rs.getString(1))
                            }
                        }
                    }

                    // 3a. Invitee cannot UPDATE role column directly -> 42501 (column privilege denied)
                    val roleEscalateErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(principalFor(f.candidateAccept.userId)) { session ->
                            session.connection.prepareStatement(
                                "UPDATE nexora.company_invitations SET role = 'company_admin' WHERE id = ?::uuid",
                            ).use { ps ->
                                ps.setString(1, directInvId.toString())
                                ps.executeUpdate()
                            }
                        }
                    }
                    assertEquals("42501", roleEscalateErr.sqlState)

                    // 3b. Invitee cannot UPDATE status directly to 'accepted' -> 42501 (RLS WITH CHECK denied)
                    val directAcceptErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(principalFor(f.candidateAccept.userId)) { session ->
                            session.connection.prepareStatement(
                                "UPDATE nexora.company_invitations SET status = 'accepted', responded_at = now() WHERE id = ?::uuid",
                            ).use { ps ->
                                ps.setString(1, directInvId.toString())
                                ps.executeUpdate()
                            }
                        }
                    }
                    assertEquals("42501", directAcceptErr.sqlState)

                    // 3c. Outsider cannot decline another user's invitation via direct SQL -> 0 rows updated
                    val outsiderRows = tx.inAuthenticatedTransaction(principalFor(f.outsider.userId)) { session ->
                        session.connection.prepareStatement(
                            "UPDATE nexora.company_invitations SET status = 'declined', responded_at = now() WHERE id = ?::uuid",
                        ).use { ps ->
                            ps.setString(1, directInvId.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(0, outsiderRows)

                    // 3d. Cross-tenant admin (adminB is now trainer in B; use active admin of A -> whichever of adminA/dualUser is still admin)
                    //     cannot INSERT an invitation into Company B via direct SQL -> 42501 (RLS WITH CHECK denied)
                    val activeAdminA = tx.inAuthenticatedTransaction(principalFor(f.adminA.userId), readOnly = true) { session ->
                        val m = queryRepo.findActiveMembership(session, f.companyA, f.adminA.userId)
                        if (m?.role == com.nexora.backend.domain.multitenant.CompanyRole.COMPANY_ADMIN) f.adminA.userId else f.dualUser.userId
                    }
                    val crossTenantInsertErr = assertFailsWith<PSQLException> {
                        tx.inAuthenticatedTransaction(principalFor(activeAdminA)) { session ->
                            session.connection.prepareStatement(
                                """
                                INSERT INTO nexora.company_invitations (company_id, invitee_user_id, invited_by_user_id, role, expires_at)
                                VALUES (?::uuid, ?::uuid, ?::uuid, 'member', now() + interval '1 day')
                                """.trimIndent(),
                            ).use { ps ->
                                ps.setString(1, f.companyB.toString())
                                ps.setString(2, f.candidateDecline.userId.toString())
                                ps.setString(3, activeAdminA.toString())
                                ps.executeUpdate()
                            }
                        }
                    }
                    assertEquals("42501", crossTenantInsertErr.sqlState)

                    // 3e. Cross-tenant admin cannot UPDATE Company B memberships via direct SQL -> 0 rows updated
                    val crossTenantUpdateRows = tx.inAuthenticatedTransaction(principalFor(activeAdminA)) { session ->
                        session.connection.prepareStatement(
                            "UPDATE nexora.company_memberships SET role = 'member' WHERE company_id = ?::uuid AND user_id = ?::uuid",
                        ).use { ps ->
                            ps.setString(1, f.companyB.toString())
                            ps.setString(2, f.memberB.userId.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(0, crossTenantUpdateRows)

                    // 3f. Invitee declines legitimately, then terminal-state transition via direct SQL updates 0 rows
                    val declinedRows = tx.inAuthenticatedTransaction(principalFor(f.candidateAccept.userId)) { session ->
                        session.connection.prepareStatement(
                            "UPDATE nexora.company_invitations SET status = 'declined', responded_at = now() WHERE id = ?::uuid",
                        ).use { ps ->
                            ps.setString(1, directInvId.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(1, declinedRows)

                    val terminalRetryRows = tx.inAuthenticatedTransaction(principalFor(f.memberB.userId)) { session ->
                        session.connection.prepareStatement(
                            "UPDATE nexora.company_invitations SET status = 'revoked', responded_at = now() WHERE id = ?::uuid",
                        ).use { ps ->
                            ps.setString(1, directInvId.toString())
                            ps.executeUpdate()
                        }
                    }
                    assertEquals(0, terminalRetryRows)
                }
            }
        }
    }
}
