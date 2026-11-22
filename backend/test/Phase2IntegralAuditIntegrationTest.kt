package com.nexora.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.auth.CachingJwksKeyProvider
import com.nexora.backend.adapter.auth.JwksFetcher
import com.nexora.backend.adapter.auth.JwtAuthConfig
import com.nexora.backend.adapter.auth.NimbusJwtAccessTokenVerifier
import com.nexora.backend.adapter.http.ktor.READINESS_TOKEN_HEADER
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
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.postgresql.util.PSQLException

/**
 * Phase 2D integral security and functional audit suite:
 * 1. HTTP contract hardening (duplicate Authorization/Readiness headers, 413 payload caps,
 *    415 content-type enforcement, trailing JSON token rejection, Cache-Control: no-store,
 *    absence of legacy demo or unintended admin routes).
 * 2. Real PostgreSQL 17 + real Supabase Auth GoTrue end-to-end verification of previously uncovered
 *    audit scenarios:
 *    - Expired pending invitations (409 invitation_expired + automatic revocation on re-invite + audit).
 *    - Revoked invitations (accept & decline both rejected with 409 invitation_not_pending).
 *    - Durable audit_events RLS scoping per company (admin A vs admin B vs non-admin vs platform NULL rows).
 *    - Concurrent profile upsert on a brand-new user (idempotent ON CONFLICT without 500).
 *    - Full transaction rollback on deferred active-admin constraint failure (zero committed role change
 *      or audit event) followed by immediate physical connection reuse on a maxPoolSize=1 HikariCP pool.
 */
class Phase2IntegralAuditIntegrationTest {
    private val jdbcUrl = System.getProperty(
        "nexora.test.localPostgresUrl",
        System.getenv("NEXORA_RUNTIME_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:55422/postgres",
    )
    private val authBaseUrl = System.getProperty("nexora.test.localAuthUrl", "http://127.0.0.1:55421").trimEnd('/')
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    private val unitIssuer = "http://127.0.0.1:55421"
    private val unitAudience = "authenticated"
    private val fixedNow = Instant.parse("2026-11-22T21:45:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun testKey(): ECKey = ECKeyGenerator(Curve.P_256)
        .keyID("audit-2d-kid")
        .keyUse(KeyUse.SIGNATURE)
        .keyOperations(setOf(KeyOperation.VERIFY))
        .algorithm(JWSAlgorithm.ES256)
        .generate()

    private fun testVerifier(key: ECKey): NimbusJwtAccessTokenVerifier {
        val cfg = JwtAuthConfig(issuer = unitIssuer, audience = unitAudience, jwksUri = URI("$unitIssuer/.well-known/jwks.json"))
        val provider = CachingJwksKeyProvider(JwksFetcher { JWKSet(key.toPublicJWK()).toString() }, clock = fixedClock)
        return NimbusJwtAccessTokenVerifier(cfg, provider, fixedClock)
    }

    private fun signToken(key: ECKey, userId: UUID = UUID.randomUUID()): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(unitIssuer)
            .audience(unitAudience)
            .subject(userId.toString())
            .issueTime(Date.from(fixedNow))
            .expirationTime(Date.from(fixedNow.plusSeconds(3600)))
            .claim("role", "authenticated")
            .claim("session_id", UUID.randomUUID().toString())
            .claim("aal", "aal1")
            .claim("is_anonymous", false)
            .build()
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType.JWT).build(),
            claims,
        )
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    @Test
    fun `http contract hardening rejects duplicate auth headers, oversized JSON bodies, wrong content types, and unintended routes`() =
        testApplication {
            val key = testKey()
            val tokenA = signToken(key)
            val tokenB = signToken(key)
            val cfg = AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "staging",
                    "NEXORA_READINESS_TOKEN" to "ops-secret-2d",
                    "NEXORA_AUTH_ISSUER" to "https://audit.example.supabase.co/auth/v1",
                    "NEXORA_AUTH_JWKS_URL" to "https://audit.example.supabase.co/auth/v1/.well-known/jwks.json",
                ),
            )
            // Dummy services that throw if reached (validation/body/auth guards must trigger before DB).
            val dummyTx = object : com.nexora.backend.application.persistence.TransactionManager {
                override suspend fun <T> inAuthenticatedTransaction(
                    principal: AuthenticatedPrincipal,
                    readOnly: Boolean,
                    block: (com.nexora.backend.application.persistence.TransactionalSession) -> T,
                ): T = error("must not touch DB")

                override suspend fun <T> inInfrastructureTransaction(
                    readOnly: Boolean,
                    block: (com.nexora.backend.application.persistence.TransactionalSession) -> T,
                ): T = error("must not touch DB")
            }

            application {
                nexoraSaasModule(
                    config = cfg,
                    accessTokenVerifier = testVerifier(key),
                    profileService = ProfileService(dummyTx, PostgresProfileRepository()),
                    companyQueryService = CompanyQueryService(dummyTx, PostgresCompanyQueryRepository()),
                    membershipManagementService = MembershipManagementService(
                        dummyTx,
                        PostgresCompanyQueryRepository(),
                        PostgresMembershipManagementRepository(),
                    ),
                )
            }

            // 1. Duplicate Authorization headers must be rejected with 401 invalid_authorization_header
            val dupAuth = client.get("/api/v1/auth/me") {
                header(HttpHeaders.Authorization, "Bearer $tokenA")
                header(HttpHeaders.Authorization, "Bearer $tokenB")
            }
            assertEquals(HttpStatusCode.Unauthorized, dupAuth.status)
            assertEquals("invalid_authorization_header", mapper.readTree(dupAuth.bodyAsText())["error"]["code"].asText())

            // 2. /health/ready sets Cache-Control: no-store and rejects duplicate readiness tokens
            val dupReady = client.get("/health/ready") {
                header(READINESS_TOKEN_HEADER, "ops-secret-2d")
                header(READINESS_TOKEN_HEADER, "ops-secret-2d")
            }
            assertEquals(HttpStatusCode.Unauthorized, dupReady.status)
            assertEquals("no-store", dupReady.headers[HttpHeaders.CacheControl])

            val okReady = client.get("/health/ready") {
                header(READINESS_TOKEN_HEADER, "ops-secret-2d")
            }
            assertEquals(HttpStatusCode.OK, okReady.status)
            assertEquals("no-store", okReady.headers[HttpHeaders.CacheControl])

            // 3. Oversized JSON payload (> 16 KiB) rejected with 413 payload_too_large on profile & invitation routes
            val oversizedProfile = """{"displayName":"${"a".repeat(20_000)}"}"""
            val tooLargeProfile = client.put("/api/v1/me/profile") {
                header(HttpHeaders.Authorization, "Bearer $tokenA")
                contentType(ContentType.Application.Json)
                setBody(oversizedProfile)
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLargeProfile.status)
            assertEquals("payload_too_large", mapper.readTree(tooLargeProfile.bodyAsText())["error"]["code"].asText())

            val oversizedInvite = """{"inviteeUserId":"${UUID.randomUUID()}","role":"${"m".repeat(20_000)}"}"""
            val tooLargeInvite = client.post("/api/v1/companies/${UUID.randomUUID()}/invitations") {
                header(HttpHeaders.Authorization, "Bearer $tokenA")
                contentType(ContentType.Application.Json)
                setBody(oversizedInvite)
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLargeInvite.status)
            assertEquals("payload_too_large", mapper.readTree(tooLargeInvite.bodyAsText())["error"]["code"].asText())

            // 4. Non-JSON Content-Type rejected with 415 unsupported_media_type
            val wrongType = client.put("/api/v1/me/profile") {
                header(HttpHeaders.Authorization, "Bearer $tokenA")
                contentType(ContentType.Text.Plain)
                setBody("""{"displayName":"Alice"}""")
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, wrongType.status)
            assertEquals("unsupported_media_type", mapper.readTree(wrongType.bodyAsText())["error"]["code"].asText())

            // 5. Trailing JSON tokens rejected with 400 invalid_json
            val trailingJson = client.put("/api/v1/me/profile") {
                header(HttpHeaders.Authorization, "Bearer $tokenA")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Alice"}{"extra":true}""")
            }
            assertEquals(HttpStatusCode.BadRequest, trailingJson.status)
            assertEquals("invalid_json", mapper.readTree(trailingJson.bodyAsText())["error"]["code"].asText())

            // 6. Legacy demo routes and unintended admin/company-creation routes do not exist
            for (path in listOf("/api/communities", "/health", "/api/v1/admin", "/api/v1/platform/users")) {
                val resp = client.get(path) { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                assertEquals(HttpStatusCode.NotFound, resp.status, "Unexpected route $path")
                assertNotNull(resp.headers[HttpHeaders.XRequestId])
            }
            val unexposedCreateCompany = client.post("/api/v1/companies") {
                header(HttpHeaders.Authorization, "Bearer $tokenA")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"evil","name":"Evil"}""")
            }
            assertTrue(
                unexposedCreateCompany.status in setOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed),
                "POST /api/v1/companies must never exist; got ${unexposedCreateCompany.status}",
            )
        }

    private data class AuthActor(
        val userId: UUID,
        val accessToken: String,
    )

    private fun requireLocalPostgresAndAuth() {
        val enabled = System.getProperty("nexora.test.localPostgres", "false").equals("true", ignoreCase = true)
        Assume.assumeTrue("Set -Dnexora.test.localPostgres=true to run Phase 2D integration audit", enabled)
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

    private fun openMigratorConnection(): Connection {
        val props = Properties().apply {
            setProperty("user", "app_migrator")
            setProperty("password", migratorPassword())
            setProperty("sslmode", "prefer")
        }
        return DriverManager.getConnection(jdbcUrl, props)
    }

    private fun createLocalAuthActor(): AuthActor {
        val email = "nexora-2d-${UUID.randomUUID()}@example.test"
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

    private fun localAppConfig(): AppConfig = AppConfig.fromEnv(
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
    fun `integral audit verifies expired and revoked invitations, audit_events RLS scoping, concurrent profile upsert, and rollback pool reuse`() {
        requireLocalPostgresAndAuth()

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

        val companyA = UUID.randomUUID()
        val companyB = UUID.randomUUID()
        val adminA = createLocalAuthActor()
        val trainerA = createLocalAuthActor()
        val memberA = createLocalAuthActor()
        val adminB = createLocalAuthActor()
        val candidateExpired = createLocalAuthActor()
        val candidateB = createLocalAuthActor()
        val concurrentProfileUser = createLocalAuthActor() // intentionally NO initial profile
        val outsider = createLocalAuthActor()

        val seededUsersWithProfiles = listOf(adminA, trainerA, memberA, adminB, candidateExpired, candidateB, outsider)
        val allUsers = seededUsersWithProfiles + concurrentProfileUser

        openMigratorConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("INSERT INTO nexora.profiles (id, display_name) VALUES (?::uuid, ?)").use { ps ->
                    seededUsersWithProfiles.forEachIndexed { idx, actor ->
                        ps.setString(1, actor.userId.toString())
                        ps.setString(2, "Audit2D User $idx")
                        ps.executeUpdate()
                    }
                }
                conn.prepareStatement("INSERT INTO nexora.companies (id, slug, name, status) VALUES (?::uuid, ?, ?, 'active')").use { ps ->
                    ps.setString(1, companyA.toString())
                    ps.setString(2, "audit2d-a-${companyA.toString().take(8)}")
                    ps.setString(3, "Audit2D Alpha")
                    ps.executeUpdate()

                    ps.setString(1, companyB.toString())
                    ps.setString(2, "audit2d-b-${companyB.toString().take(8)}")
                    ps.setString(3, "Audit2D Beta")
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
                    addM(companyA, adminA, "company_admin")
                    addM(companyA, trainerA, "trainer")
                    addM(companyA, memberA, "member")
                    addM(companyB, adminB, "company_admin")
                }
                conn.commit()
            } catch (ex: Throwable) {
                conn.rollback()
                throw ex
            }
        }

        try {
            runtimePool(maxPoolSize = 4).use { httpPool ->
                val tx = JdbcTransactionManager(httpPool)
                val queryRepo = PostgresCompanyQueryRepository()
                val mgmtRepo = PostgresMembershipManagementRepository()
                val profileService = ProfileService(tx, PostgresProfileRepository())
                val companyService = CompanyQueryService(tx, queryRepo)
                val mgmtService = MembershipManagementService(tx, queryRepo, mgmtRepo)

                testApplication {
                    application {
                        nexoraSaasModule(
                            config = localAppConfig(),
                            profileService = profileService,
                            companyQueryService = companyService,
                            membershipManagementService = mgmtService,
                        )
                    }

                    // --- A. Expired invitation lifecycle ---
                    // Seed an already-expired pending invitation directly via fixture/migrator
                    // (satisfies CHECK (expires_at > created_at) without mutating immutable expires_at).
                    val expiredInvitationId = UUID.randomUUID()
                    openMigratorConnection().use { conn ->
                        conn.autoCommit = false
                        conn.prepareStatement(
                            """
                            INSERT INTO nexora.company_invitations
                                (id, company_id, invitee_user_id, invited_by_user_id, role, status, expires_at, created_at, updated_at)
                            VALUES
                                (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'member', 'pending',
                                 now() - interval '2 minutes', now() - interval '10 minutes', now() - interval '10 minutes')
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setString(1, expiredInvitationId.toString())
                            ps.setString(2, companyA.toString())
                            ps.setString(3, candidateExpired.userId.toString())
                            ps.setString(4, adminA.userId.toString())
                            assertEquals(1, ps.executeUpdate())
                        }
                        conn.commit()
                    }

                    // Accepting an expired invitation MUST fail with 409 invitation_expired and create NO membership
                    val acceptExpired = client.post("/api/v1/me/invitations/$expiredInvitationId/accept") {
                        header(HttpHeaders.Authorization, "Bearer ${candidateExpired.accessToken}")
                    }
                    assertEquals(HttpStatusCode.Conflict, acceptExpired.status)
                    assertEquals("invitation_expired", mapper.readTree(acceptExpired.bodyAsText())["error"]["code"].asText())

                    openMigratorConnection().use { conn ->
                        conn.prepareStatement(
                            "SELECT COUNT(*) FROM nexora.company_memberships WHERE company_id = ?::uuid AND user_id = ?::uuid",
                        ).use { ps ->
                            ps.setString(1, companyA.toString())
                            ps.setString(2, candidateExpired.userId.toString())
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                assertEquals(0, rs.getInt(1), "Expired invitation accept must not create a membership")
                            }
                        }
                    }

                    // Re-inviting automatically revokes the expired pending invitation (with audit) and issues a fresh one
                    val reinviteResp = client.post("/api/v1/companies/$companyA/invitations") {
                        header(HttpHeaders.Authorization, "Bearer ${adminA.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"inviteeUserId":"${candidateExpired.userId}","role":"trainer"}""")
                    }
                    assertEquals(HttpStatusCode.Created, reinviteResp.status)
                    val freshInvitationId = UUID.fromString(mapper.readTree(reinviteResp.bodyAsText())["id"].asText())
                    assertNotEquals(expiredInvitationId, freshInvitationId)

                    // --- B. Revoked invitation accept & decline rejection ---
                    val acceptRevoked = client.post("/api/v1/me/invitations/$expiredInvitationId/accept") {
                        header(HttpHeaders.Authorization, "Bearer ${candidateExpired.accessToken}")
                    }
                    assertEquals(HttpStatusCode.Conflict, acceptRevoked.status)
                    assertEquals("invitation_not_pending", mapper.readTree(acceptRevoked.bodyAsText())["error"]["code"].asText())

                    val declineRevoked = client.post("/api/v1/me/invitations/$expiredInvitationId/decline") {
                        header(HttpHeaders.Authorization, "Bearer ${candidateExpired.accessToken}")
                    }
                    assertEquals(HttpStatusCode.Conflict, declineRevoked.status)
                    assertEquals("invitation_not_pending", mapper.readTree(declineRevoked.bodyAsText())["error"]["code"].asText())

                    // Verify expiredInvitationId status is 'revoked' and audit row 'membership.invitation_revoked' exists
                    openMigratorConnection().use { conn ->
                        conn.prepareStatement("SELECT status FROM nexora.company_invitations WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, expiredInvitationId.toString())
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                assertEquals("revoked", rs.getString(1))
                            }
                        }
                        conn.prepareStatement(
                            "SELECT COUNT(*) FROM nexora.audit_events WHERE company_id = ?::uuid AND action = 'membership.invitation_revoked' AND target_id = ?",
                        ).use { ps ->
                            ps.setString(1, companyA.toString())
                            ps.setString(2, expiredInvitationId.toString())
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                assertEquals(1, rs.getInt(1), "Revoking expired invitation must emit durable audit event")
                            }
                        }
                    }

                    // Accepting the fresh invitation succeeds
                    val acceptFresh = client.post("/api/v1/me/invitations/$freshInvitationId/accept") {
                        header(HttpHeaders.Authorization, "Bearer ${candidateExpired.accessToken}")
                    }
                    assertEquals(HttpStatusCode.OK, acceptFresh.status)
                    assertEquals("trainer", mapper.readTree(acceptFresh.bodyAsText())["role"].asText())

                    // Create an audit event in Company B too so we can test cross-company audit RLS scoping
                    val inviteB = client.post("/api/v1/companies/$companyB/invitations") {
                        header(HttpHeaders.Authorization, "Bearer ${adminB.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"inviteeUserId":"${candidateB.userId}","role":"member"}""")
                    }
                    assertEquals(HttpStatusCode.Created, inviteB.status)

                    // --- D. Concurrent profile upsert for a brand-new user ---
                    val upsertStatuses = runBlocking {
                        coroutineScope {
                            listOf("Alpha Name", "Beta Name").map { displayName ->
                                async {
                                    client.put("/api/v1/me/profile") {
                                        header(HttpHeaders.Authorization, "Bearer ${concurrentProfileUser.accessToken}")
                                        contentType(ContentType.Application.Json)
                                        setBody("""{"displayName":"$displayName"}""")
                                    }.status
                                }
                            }.awaitAll()
                        }
                    }
                    assertTrue(upsertStatuses.all { it == HttpStatusCode.OK }, "Both concurrent profile upserts must return 200 OK, got $upsertStatuses")
                    openMigratorConnection().use { conn ->
                        conn.prepareStatement("SELECT COUNT(*) FROM nexora.profiles WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, concurrentProfileUser.userId.toString())
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                assertEquals(1, rs.getInt(1))
                            }
                        }
                    }
                }
            }

            // --- C. Durable audit_events RLS scoping as app_runtime + E. Rollback & maxPoolSize=1 reuse ---
            runtimePool(maxPoolSize = 1).use { singlePool ->
                val singleTx = JdbcTransactionManager(singlePool)

                fun principalOf(actor: AuthActor) = AuthenticatedPrincipal(
                    userId = actor.userId,
                    issuer = authBaseUrl,
                    audience = "authenticated",
                    sessionId = UUID.randomUUID(),
                    aal = "aal1",
                    expiresAt = Instant.now().plusSeconds(3600),
                )

                runBlocking {
                    // C1. Verify role preconditions on app_runtime
                    singleTx.inInfrastructureTransaction(readOnly = true) { s ->
                        s.connection.createStatement().use { st ->
                            st.executeQuery(
                                "SELECT current_user, rolsuper, rolbypassrls, rolcreatedb, rolcreaterole, rolinherit " +
                                    "FROM pg_roles WHERE rolname = current_user",
                            ).use { rs ->
                                assertTrue(rs.next())
                                assertEquals("app_runtime", rs.getString(1))
                                assertFalse(rs.getBoolean(2), "app_runtime must not be superuser")
                                assertFalse(rs.getBoolean(3), "app_runtime must not bypass RLS")
                                assertFalse(rs.getBoolean(4), "app_runtime must not have createdb")
                                assertFalse(rs.getBoolean(5), "app_runtime must not have createrole")
                                assertFalse(rs.getBoolean(6), "app_runtime must not inherit")
                            }
                        }
                    }

                    // C2. Admin A sees ONLY Company A audit events (0 from Company B, 0 platform NULL events)
                    singleTx.inAuthenticatedTransaction(principalOf(adminA), readOnly = true) { s ->
                        val countA = countAuditEvents(s.connection, "WHERE company_id = ?::uuid", companyA)
                        val countB = countAuditEvents(s.connection, "WHERE company_id = ?::uuid", companyB)
                        val countPlatformNull = countAuditEvents(s.connection, "WHERE company_id IS NULL", null)
                        assertTrue(countA >= 3, "adminA must see Company A audit events, got $countA")
                        assertEquals(0, countB, "adminA must NEVER see Company B audit events")
                        assertEquals(0, countPlatformNull, "adminA must NEVER see platform (company_id IS NULL) audit events")
                    }

                    // C3. Admin B sees ONLY Company B audit events (0 from Company A, 0 platform NULL events)
                    singleTx.inAuthenticatedTransaction(principalOf(adminB), readOnly = true) { s ->
                        val countA = countAuditEvents(s.connection, "WHERE company_id = ?::uuid", companyA)
                        val countB = countAuditEvents(s.connection, "WHERE company_id = ?::uuid", companyB)
                        val countPlatformNull = countAuditEvents(s.connection, "WHERE company_id IS NULL", null)
                        assertEquals(0, countA, "adminB must NEVER see Company A audit events")
                        assertTrue(countB >= 1, "adminB must see Company B audit events, got $countB")
                        assertEquals(0, countPlatformNull, "adminB must NEVER see platform audit events")
                    }

                    // C4. Non-admins (trainerA, memberA, outsider) and no-identity see 0 audit_events rows
                    for (nonAdmin in listOf(trainerA, memberA, outsider)) {
                        singleTx.inAuthenticatedTransaction(principalOf(nonAdmin), readOnly = true) { s ->
                            assertEquals(0, countAuditEvents(s.connection, "", null), "Non-admin must see 0 audit_events rows")
                        }
                    }
                    singleTx.inInfrastructureTransaction(readOnly = true) { s ->
                        assertEquals(0, countAuditEvents(s.connection, "", null), "No-identity must see 0 audit_events rows")
                    }

                    // E. Forced rollback at COMMIT (last active admin deferred trigger) + single-connection reuse
                    var firstBackendPid = -1
                    val roleChangesBefore = openMigratorConnection().use { conn ->
                        conn.prepareStatement(
                            "SELECT COUNT(*) FROM nexora.audit_events WHERE company_id = ?::uuid AND action = 'membership.role_changed'",
                        ).use { ps ->
                            ps.setString(1, companyB.toString())
                            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                        }
                    }

                    val ex = assertFailsWith<PSQLException> {
                        singleTx.inAuthenticatedTransaction(principalOf(adminB), readOnly = false) { s ->
                            s.connection.createStatement().use { st ->
                                st.executeQuery("SELECT pg_backend_pid()").use { rs ->
                                    assertTrue(rs.next())
                                    firstBackendPid = rs.getInt(1)
                                }
                            }
                            // Attempt to demote sole active admin of Company B -> fails at deferred COMMIT
                            s.connection.prepareStatement(
                                "UPDATE nexora.company_memberships SET role = 'member' WHERE company_id = ?::uuid AND user_id = ?::uuid",
                            ).use { ps ->
                                ps.setString(1, companyB.toString())
                                ps.setString(2, adminB.userId.toString())
                                ps.executeUpdate()
                            }
                        }
                    }
                    assertEquals("23514", ex.sqlState, "Expected deferred active-admin violation SQLSTATE 23514")
                    assertTrue(firstBackendPid > 0)

                    // Confirm ZERO committed side effects (role unchanged, zero audit row committed)
                    openMigratorConnection().use { conn ->
                        conn.prepareStatement(
                            "SELECT role, status FROM nexora.company_memberships WHERE company_id = ?::uuid AND user_id = ?::uuid",
                        ).use { ps ->
                            ps.setString(1, companyB.toString())
                            ps.setString(2, adminB.userId.toString())
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                assertEquals("company_admin", rs.getString(1))
                                assertEquals("active", rs.getString(2))
                            }
                        }
                        conn.prepareStatement(
                            "SELECT COUNT(*) FROM nexora.audit_events WHERE company_id = ?::uuid AND action = 'membership.role_changed'",
                        ).use { ps ->
                            ps.setString(1, companyB.toString())
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                assertEquals(roleChangesBefore, rs.getInt(1), "Rolled-back role change must not commit any audit row")
                            }
                        }
                    }

                    // Immediately reuse the single-connection pool as memberA (note: commit failure evicts tainted conn,
                    // so test both clean recovery AND reuse of a clean connection across rollback-before-commit)
                    singleTx.inAuthenticatedTransaction(principalOf(memberA), readOnly = true) { s ->
                        s.connection.createStatement().use { st ->
                            st.executeQuery("SELECT current_setting('app.user_id', true)").use { rs ->
                                assertTrue(rs.next())
                                assertEquals(memberA.userId.toString(), rs.getString(1))
                            }
                        }
                        s.connection.prepareStatement("SELECT COUNT(*) FROM nexora.companies WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, companyB.toString())
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                assertEquals(0, rs.getInt(1), "memberA must never see Company B after rollback recovery")
                            }
                        }
                    }

                    // Rollback inside block (non-evicted physical connection reuse on maxPoolSize=1)
                    var rollbackPid = -1
                    assertFailsWith<IllegalStateException> {
                        singleTx.inAuthenticatedTransaction(principalOf(adminA), readOnly = false) { s ->
                            s.connection.createStatement().use { st ->
                                st.executeQuery("SELECT pg_backend_pid()").use { rs ->
                                    assertTrue(rs.next())
                                    rollbackPid = rs.getInt(1)
                                }
                            }
                            s.connection.prepareStatement(
                                "UPDATE nexora.profiles SET display_name = 'SHOULD_ROLLBACK' WHERE id = ?::uuid",
                            ).use { ps ->
                                ps.setString(1, adminA.userId.toString())
                                ps.executeUpdate()
                            }
                            throw IllegalStateException("simulated mid-tx failure")
                        }
                    }

                    // Next transaction on the SAME physical connection (maxPoolSize=1) without identity must have empty app.user_id
                    singleTx.inInfrastructureTransaction(readOnly = true) { s ->
                        s.connection.createStatement().use { st ->
                            st.executeQuery("SELECT pg_backend_pid(), COALESCE(current_setting('app.user_id', true), ''), (SELECT COUNT(*) FROM nexora.companies)").use { rs ->
                                assertTrue(rs.next())
                                assertEquals(rollbackPid, rs.getInt(1), "Expected physical connection reuse on maxPoolSize=1 pool")
                                assertEquals("", rs.getString(2), "app.user_id must be empty after rollback on reused connection")
                                assertEquals(0, rs.getInt(3), "No-identity query on reused connection must see 0 companies")
                            }
                        }
                    }
                }
            }
        } finally {
            openMigratorConnection().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement("UPDATE nexora.companies SET status = 'suspended' WHERE id = ?::uuid").use { ps ->
                        for (co in listOf(companyA, companyB)) { ps.setString(1, co.toString()); ps.executeUpdate() }
                    }
                    conn.prepareStatement("DELETE FROM nexora.company_invitations WHERE company_id = ?::uuid").use { ps ->
                        for (co in listOf(companyA, companyB)) { ps.setString(1, co.toString()); ps.executeUpdate() }
                    }
                    conn.prepareStatement("DELETE FROM nexora.company_memberships WHERE company_id = ?::uuid").use { ps ->
                        for (co in listOf(companyA, companyB)) { ps.setString(1, co.toString()); ps.executeUpdate() }
                    }
                    conn.prepareStatement("DELETE FROM nexora.companies WHERE id = ?::uuid").use { ps ->
                        for (co in listOf(companyA, companyB)) { ps.setString(1, co.toString()); ps.executeUpdate() }
                    }
                    conn.prepareStatement("DELETE FROM nexora.profiles WHERE id = ?::uuid").use { ps ->
                        for (u in allUsers) { ps.setString(1, u.userId.toString()); ps.executeUpdate() }
                    }
                    conn.commit()
                } catch (_: Throwable) {
                    runCatching { conn.rollback() }
                }
            }
        }
    }

    private fun countAuditEvents(conn: Connection, whereClause: String, companyId: UUID?): Int {
        val sql = "SELECT COUNT(*) FROM nexora.audit_events $whereClause"
        conn.prepareStatement(sql).use { ps ->
            if (companyId != null) {
                ps.setString(1, companyId.toString())
            }
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getInt(1)
            }
        }
    }
}
