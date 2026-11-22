package com.nexora.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.adapter.persistence.postgres.DatabaseConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayMigrator
import com.nexora.backend.adapter.persistence.postgres.JdbcTransactionManager
import com.nexora.backend.adapter.persistence.postgres.PostgresCompanyQueryRepository
import com.nexora.backend.adapter.persistence.postgres.PostgresProfileRepository
import com.nexora.backend.adapter.persistence.postgres.PostgresRuntimePool
import com.nexora.backend.application.multitenant.CompanyQueryService
import com.nexora.backend.application.multitenant.ProfileService
import com.nexora.backend.bootstrap.AppConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real end-to-end integration tests for Phase 2C.1 combining:
 * - Real Supabase Auth local JWTs (ES256 verified against live /.well-known/jwks.json)
 * - Real Ktor HTTP routes (/api/v1/me/profile, /api/v1/me/companies, /api/v1/companies/{id}...)
 * - Real PostgreSQL 17 + HikariCP (app_runtime) + TransactionManager (app.user_id) + V3 RLS policies
 *
 * Opt-in via `-Dnexora.test.localPostgres=true` with passwords injected solely via environment variables.
 */
class MultitenantApiPostgresIntegrationTest {
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
    )

    private fun requireEnabled() {
        val enabled = System.getProperty("nexora.test.localPostgres", "false").equals("true", ignoreCase = true)
        Assume.assumeTrue("Set -Dnexora.test.localPostgres=true to run Phase 2C.1 end-to-end tests", enabled)
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

    private fun createLocalAuthActor(): AuthActor {
        val email = "nexora-2c1-${UUID.randomUUID()}@example.test"
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

    @Test
    fun `profile endpoints handle first access, idempotent upsert, concurrent creation, updates, and spoofing resistance`() {
        requireEnabled()
        runFlyway()

        val alice = createLocalAuthActor()
        val bob = createLocalAuthActor()

        try {
            runtimePool(maxPoolSize = 3).use { pool ->
                val txManager = JdbcTransactionManager(pool)
                val profileService = ProfileService(txManager, PostgresProfileRepository())
                val companyService = CompanyQueryService(txManager, PostgresCompanyQueryRepository())

                testApplication {
                    application {
                        nexoraSaasModule(
                            config = appConfig(),
                            profileService = profileService,
                            companyQueryService = companyService,
                        )
                    }

                    // 1. First access without profile returns 404 profile_not_found
                    val initialGet = client.get("/api/v1/me/profile") {
                        header(HttpHeaders.Authorization, "Bearer ${alice.accessToken}")
                    }
                    assertEquals(HttpStatusCode.NotFound, initialGet.status)
                    assertEquals("profile_not_found", mapper.readTree(initialGet.bodyAsText())["error"]["code"].asText())

                    // 2. Concurrent first-time creation for Alice: both PUTs succeed idempotently
                    val concurrentResponses = coroutineScope {
                        listOf(
                            async {
                                client.put("/api/v1/me/profile") {
                                    header(HttpHeaders.Authorization, "Bearer ${alice.accessToken}")
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"displayName":"  Alice Initial  ","avatarPath":"avatars/alice.png"}""")
                                }
                            },
                            async {
                                client.put("/api/v1/me/profile") {
                                    header(HttpHeaders.Authorization, "Bearer ${alice.accessToken}")
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"displayName":"  Alice Initial  ","avatarPath":"avatars/alice.png"}""")
                                }
                            },
                        ).awaitAll()
                    }
                    for (resp in concurrentResponses) {
                        assertEquals(HttpStatusCode.OK, resp.status)
                        assertEquals("no-store", resp.headers[HttpHeaders.CacheControl])
                        val node = mapper.readTree(resp.bodyAsText())
                        assertEquals(alice.userId.toString(), node["id"].asText())
                        assertEquals("Alice Initial", node["displayName"].asText())
                        assertEquals("avatars/alice.png", node["avatarPath"].asText())
                    }

                    // 3. Repeated update clears avatarPath and preserves createdAt; spoofing headers are ignored
                    val createdAt = mapper.readTree(concurrentResponses[0].bodyAsText())["createdAt"].asText()
                    val updated = client.put("/api/v1/me/profile?userId=${bob.userId}") {
                        header(HttpHeaders.Authorization, "Bearer ${alice.accessToken}")
                        header("X-User-Id", bob.userId.toString())
                        contentType(ContentType.Application.Json)
                        setBody("""{"displayName":"Alice Updated","avatarPath":null}""")
                    }
                    assertEquals(HttpStatusCode.OK, updated.status)
                    val updatedNode = mapper.readTree(updated.bodyAsText())
                    assertEquals(alice.userId.toString(), updatedNode["id"].asText())
                    assertEquals("Alice Updated", updatedNode["displayName"].asText())
                    assertNull(updatedNode["avatarPath"]?.textValue())
                    assertEquals(createdAt, updatedNode["createdAt"].asText())

                    // 4. Bob still has no profile (Alice's spoofed header/query never touched Bob) and cannot read Alice
                    val bobGet = client.get("/api/v1/me/profile?userId=${alice.userId}") {
                        header(HttpHeaders.Authorization, "Bearer ${bob.accessToken}")
                        header("X-User-Id", alice.userId.toString())
                    }
                    assertEquals(HttpStatusCode.NotFound, bobGet.status)

                    // 5. Alice reads her updated profile
                    val aliceGet = client.get("/api/v1/me/profile") {
                        header(HttpHeaders.Authorization, "Bearer ${alice.accessToken}")
                    }
                    assertEquals(HttpStatusCode.OK, aliceGet.status)
                    assertEquals("no-store", aliceGet.headers[HttpHeaders.CacheControl])
                    assertEquals("Alice Updated", mapper.readTree(aliceGet.bodyAsText())["displayName"].asText())
                }
            }
        } finally {
            openMigratorConnection().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement("DELETE FROM nexora.profiles WHERE id = ?::uuid").use { ps ->
                        for (uid in listOf(alice.userId, bob.userId)) {
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
    fun `company and membership endpoints enforce real RLS isolation across roles, statuses, and reused pool connections`() {
        requireEnabled()
        runFlyway()

        val adminA = createLocalAuthActor()
        val trainerA = createLocalAuthActor()
        val memberA = createLocalAuthActor()
        val invitedA = createLocalAuthActor()
        val suspendedA = createLocalAuthActor()
        val adminB = createLocalAuthActor()
        val dualUser = createLocalAuthActor()
        val outsider = createLocalAuthActor()
        val suspendedCoAdmin = createLocalAuthActor()

        val companyA = UUID.randomUUID()
        val companyB = UUID.randomUUID()
        val companySuspended = UUID.randomUUID()

        val allActors = listOf(adminA, trainerA, memberA, invitedA, suspendedA, adminB, dualUser, outsider, suspendedCoAdmin)
        val allCompanies = listOf(companyA, companyB, companySuspended)

        openMigratorConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("INSERT INTO nexora.profiles (id, display_name) VALUES (?::uuid, ?)").use { ps ->
                    allActors.forEachIndexed { idx, actor ->
                        ps.setString(1, actor.userId.toString())
                        ps.setString(2, "API User $idx")
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
                    addCo(companyA, "api-a", "Alpha Academy", "active")
                    addCo(companyB, "api-b", "Beta Institute", "active")
                    addCo(companySuspended, "api-s", "Suspended Corp", "suspended")
                }
                conn.prepareStatement(
                    "INSERT INTO nexora.company_memberships (company_id, user_id, role, status) VALUES (?::uuid, ?::uuid, ?, ?)",
                ).use { ps ->
                    fun addM(co: UUID, actor: AuthActor, role: String, status: String) {
                        ps.setString(1, co.toString())
                        ps.setString(2, actor.userId.toString())
                        ps.setString(3, role)
                        ps.setString(4, status)
                        ps.executeUpdate()
                    }
                    // Company A: 6 memberships
                    addM(companyA, adminA, "company_admin", "active")
                    addM(companyA, trainerA, "trainer", "active")
                    addM(companyA, memberA, "member", "active")
                    addM(companyA, invitedA, "member", "invited")
                    addM(companyA, suspendedA, "member", "suspended")
                    addM(companyA, dualUser, "company_admin", "active")
                    // Company B: 2 memberships
                    addM(companyB, adminB, "company_admin", "active")
                    addM(companyB, dualUser, "member", "active")
                    // Suspended company: 1 membership
                    addM(companySuspended, suspendedCoAdmin, "company_admin", "active")
                }
                conn.commit()
            } catch (ex: Throwable) {
                conn.rollback()
                throw ex
            }
        }

        try {
            // Single-connection HikariCP pool proves zero cross-user leakage across reused physical connections
            runtimePool(maxPoolSize = 1).use { pool ->
                val txManager = JdbcTransactionManager(pool)
                val profileService = ProfileService(txManager, PostgresProfileRepository())
                val companyService = CompanyQueryService(txManager, PostgresCompanyQueryRepository())

                testApplication {
                    application {
                        nexoraSaasModule(
                            config = appConfig(),
                            profileService = profileService,
                            companyQueryService = companyService,
                        )
                    }

                    // 1. Outsider, invited, suspended, and suspended-company user see 0 companies and 404 everywhere
                    for (actor in listOf(outsider, invitedA, suspendedA, suspendedCoAdmin)) {
                        val listResp = client.get("/api/v1/me/companies") {
                            header(HttpHeaders.Authorization, "Bearer ${actor.accessToken}")
                        }
                        assertEquals(HttpStatusCode.OK, listResp.status)
                        val listJson = mapper.readTree(listResp.bodyAsText())
                        assertEquals(0, listJson["items"].size())
                        assertFalse(listJson["hasMore"].asBoolean())

                        for (targetCompany in listOf(companyA, companyB, companySuspended)) {
                            for (suffix in listOf("", "/membership", "/memberships")) {
                                val r = client.get("/api/v1/companies/$targetCompany$suffix") {
                                    header(HttpHeaders.Authorization, "Bearer ${actor.accessToken}")
                                }
                                assertEquals(HttpStatusCode.NotFound, r.status, "Expected uniform 404 for $targetCompany$suffix")
                                assertEquals("company_not_found", mapper.readTree(r.bodyAsText())["error"]["code"].asText())
                            }
                        }
                    }

                    // 2. Admin A sees Company A + all 6 memberships with pagination, and uniform 404 for Company B / suspended
                    val adminACompanies = client.get("/api/v1/me/companies") {
                        header(HttpHeaders.Authorization, "Bearer ${adminA.accessToken}")
                    }
                    assertEquals(HttpStatusCode.OK, adminACompanies.status)
                    val adminAItems = mapper.readTree(adminACompanies.bodyAsText())["items"]
                    assertEquals(1, adminAItems.size())
                    assertEquals(companyA.toString(), adminAItems[0]["id"].asText())
                    assertEquals("company_admin", adminAItems[0]["role"].asText())

                    val adminADetail = client.get("/api/v1/companies/$companyA") {
                        header(HttpHeaders.Authorization, "Bearer ${adminA.accessToken}")
                    }
                    assertEquals(HttpStatusCode.OK, adminADetail.status)
                    assertEquals("Alpha Academy", mapper.readTree(adminADetail.bodyAsText())["name"].asText())

                    val adminAMembership = client.get("/api/v1/companies/$companyA/membership") {
                        header(HttpHeaders.Authorization, "Bearer ${adminA.accessToken}")
                    }
                    assertEquals(HttpStatusCode.OK, adminAMembership.status)
                    assertEquals("company_admin", mapper.readTree(adminAMembership.bodyAsText())["role"].asText())

                    // Paginated memberships of Company A (limit=2 -> 3 pages covering all 6 rows)
                    val page1 = mapper.readTree(
                        client.get("/api/v1/companies/$companyA/memberships?limit=2&offset=0") {
                            header(HttpHeaders.Authorization, "Bearer ${adminA.accessToken}")
                        }.bodyAsText(),
                    )
                    assertEquals(2, page1["items"].size())
                    assertTrue(page1["hasMore"].asBoolean())

                    val page3 = mapper.readTree(
                        client.get("/api/v1/companies/$companyA/memberships?limit=2&offset=4") {
                            header(HttpHeaders.Authorization, "Bearer ${adminA.accessToken}")
                        }.bodyAsText(),
                    )
                    assertEquals(2, page3["items"].size())
                    assertFalse(page3["hasMore"].asBoolean())

                    // Admin A manipulating companyId to Company B gets uniform 404
                    assertEquals(
                        HttpStatusCode.NotFound,
                        client.get("/api/v1/companies/$companyB") {
                            header(HttpHeaders.Authorization, "Bearer ${adminA.accessToken}")
                        }.status,
                    )
                    assertEquals(
                        HttpStatusCode.NotFound,
                        client.get("/api/v1/companies/$companyB/memberships") {
                            header(HttpHeaders.Authorization, "Bearer ${adminA.accessToken}")
                        }.status,
                    )

                    // 3. Trainer A and Member A see Company A, own membership, and ONLY their own row in /memberships
                    for ((actor, expectedRole) in listOf(trainerA to "trainer", memberA to "member")) {
                        val ownMem = client.get("/api/v1/companies/$companyA/membership") {
                            header(HttpHeaders.Authorization, "Bearer ${actor.accessToken}")
                        }
                        assertEquals(HttpStatusCode.OK, ownMem.status)
                        assertEquals(expectedRole, mapper.readTree(ownMem.bodyAsText())["role"].asText())

                        val listMem = client.get("/api/v1/companies/$companyA/memberships") {
                            header(HttpHeaders.Authorization, "Bearer ${actor.accessToken}")
                        }
                        assertEquals(HttpStatusCode.OK, listMem.status)
                        val items = mapper.readTree(listMem.bodyAsText())["items"]
                        assertEquals(1, items.size(), "$expectedRole must see only own active membership via RLS")
                        assertEquals(actor.userId.toString(), items[0]["userId"].asText())

                        assertEquals(
                            HttpStatusCode.NotFound,
                            client.get("/api/v1/companies/$companyB") {
                                header(HttpHeaders.Authorization, "Bearer ${actor.accessToken}")
                            }.status,
                        )
                    }

                    // 4. Dual user (company_admin in A, member in B) sees both companies in stable order,
                    //    all 6 memberships in A, and ONLY own 1 membership in B (roles never bleed across tenants)
                    val dualCompanies = mapper.readTree(
                        client.get("/api/v1/me/companies") {
                            header(HttpHeaders.Authorization, "Bearer ${dualUser.accessToken}")
                        }.bodyAsText(),
                    )["items"]
                    assertEquals(2, dualCompanies.size())
                    assertEquals("Alpha Academy", dualCompanies[0]["name"].asText())
                    assertEquals("company_admin", dualCompanies[0]["role"].asText())
                    assertEquals("Beta Institute", dualCompanies[1]["name"].asText())
                    assertEquals("member", dualCompanies[1]["role"].asText())

                    val dualMemA = mapper.readTree(
                        client.get("/api/v1/companies/$companyA/memberships") {
                            header(HttpHeaders.Authorization, "Bearer ${dualUser.accessToken}")
                        }.bodyAsText(),
                    )["items"]
                    assertEquals(6, dualMemA.size())

                    val dualMemB = mapper.readTree(
                        client.get("/api/v1/companies/$companyB/memberships") {
                            header(HttpHeaders.Authorization, "Bearer ${dualUser.accessToken}")
                        }.bodyAsText(),
                    )["items"]
                    assertEquals(1, dualMemB.size())
                    assertEquals(dualUser.userId.toString(), dualMemB[0]["userId"].asText())

                    // 5. Alternating Admin B -> Admin A -> Outsider on the 1-connection pool confirms zero leakage
                    val adminBList = mapper.readTree(
                        client.get("/api/v1/me/companies") {
                            header(HttpHeaders.Authorization, "Bearer ${adminB.accessToken}")
                        }.bodyAsText(),
                    )["items"]
                    assertEquals(1, adminBList.size())
                    assertEquals(companyB.toString(), adminBList[0]["id"].asText())

                    val adminBMem = mapper.readTree(
                        client.get("/api/v1/companies/$companyB/memberships") {
                            header(HttpHeaders.Authorization, "Bearer ${adminB.accessToken}")
                        }.bodyAsText(),
                    )["items"]
                    assertEquals(2, adminBMem.size())

                    assertEquals(
                        HttpStatusCode.NotFound,
                        client.get("/api/v1/companies/$companyA/memberships") {
                            header(HttpHeaders.Authorization, "Bearer ${adminB.accessToken}")
                        }.status,
                    )
                }
            }
        } finally {
            openMigratorConnection().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement("UPDATE nexora.companies SET status = 'suspended' WHERE id = ?::uuid").use { ps ->
                        for (co in allCompanies) {
                            ps.setString(1, co.toString())
                            ps.executeUpdate()
                        }
                    }
                    conn.prepareStatement("DELETE FROM nexora.company_memberships WHERE company_id = ?::uuid").use { ps ->
                        for (co in allCompanies) {
                            ps.setString(1, co.toString())
                            ps.executeUpdate()
                        }
                    }
                    conn.prepareStatement("DELETE FROM nexora.companies WHERE id = ?::uuid").use { ps ->
                        for (co in allCompanies) {
                            ps.setString(1, co.toString())
                            ps.executeUpdate()
                        }
                    }
                    conn.prepareStatement("DELETE FROM nexora.profiles WHERE id = ?::uuid").use { ps ->
                        for (actor in allActors) {
                            ps.setString(1, actor.userId.toString())
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
}
