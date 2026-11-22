package com.nexora.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.auth.CachingJwksKeyProvider
import com.nexora.backend.adapter.auth.JwksFetcher
import com.nexora.backend.adapter.auth.JwtAuthConfig
import com.nexora.backend.adapter.auth.NimbusJwtAccessTokenVerifier
import com.nexora.backend.adapter.http.ktor.ClientIpResolver
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.adapter.persistence.postgres.FlywayConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayMigrator
import com.nexora.backend.adapter.persistence.postgres.PlatformDatabaseConfig
import com.nexora.backend.adapter.persistence.postgres.PostgresPlatformCompanyRepository
import com.nexora.backend.adapter.persistence.postgres.PostgresPlatformPool
import com.nexora.backend.application.auth.AuthSessionState
import com.nexora.backend.application.auth.AuthSessionVerifier
import com.nexora.backend.application.auth.DefaultGlobalAdminAuthorizer
import com.nexora.backend.application.auth.GlobalAdminPolicyConfig
import com.nexora.backend.application.platform.PlatformCompanyCreationResult
import com.nexora.backend.application.platform.PlatformCompanyRepository
import com.nexora.backend.application.platform.PlatformCompanyService
import com.nexora.backend.application.platform.PlatformRateLimiter
import com.nexora.backend.bootstrap.AppConfig
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
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.postgresql.util.PSQLException

/**
 * Adversarial security tests for Phase 2C.3C:
 * - Proxy IP spoofing (`X-Forwarded-For`, RFC 7239 `Forwarded`) vs explicit `NEXORA_TRUSTED_PROXIES`.
 * - Rate-limit audit deduplication (prevents unauthenticated flood from exhausting `nexora-platform-pool`).
 * - Header smuggling (duplicate `Authorization` / `Idempotency-Key`) and oversized body (`413`).
 * - Real PostgreSQL 17 catalog audit (all functions, search_path, owners, ACLs), V8 audit controls,
 *   rollback atomicity, and 4-way mixed concurrency without deadlocks.
 */
class PlatformAdversarialSecurityTest {
    private val json = ObjectMapper()
    private val issuer = "http://127.0.0.1:55421"
    private val audience = "authenticated"
    private val baseTime = Instant.parse("2026-11-22T21:35:00Z")
    private val fixedClock = Clock.fixed(baseTime, ZoneOffset.UTC)
    private val globalAdminId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val initialAdminId = UUID.fromString("33333333-3333-4333-8333-333333333333")

    private fun testKey(): ECKey = ECKeyGenerator(Curve.P_256)
        .keyID("adv-kid-2c3c")
        .keyUse(KeyUse.SIGNATURE)
        .keyOperations(setOf(KeyOperation.VERIFY))
        .algorithm(JWSAlgorithm.ES256)
        .generate()

    private fun testVerifier(key: ECKey): NimbusJwtAccessTokenVerifier {
        val cfg = JwtAuthConfig(issuer = issuer, audience = audience, jwksUri = URI("$issuer/.well-known/jwks.json"))
        val provider = CachingJwksKeyProvider(JwksFetcher { JWKSet(key.toPublicJWK()).toString() }, clock = fixedClock)
        return NimbusJwtAccessTokenVerifier(cfg, provider, fixedClock)
    }

    private fun signValidGlobalAdminToken(key: ECKey): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(globalAdminId.toString())
            .issueTime(Date.from(baseTime.minusSeconds(5)))
            .expirationTime(Date.from(baseTime.plusSeconds(3600)))
            .claim("role", "authenticated")
            .claim("is_anonymous", false)
            .claim("session_id", UUID.randomUUID().toString())
            .claim("aal", "aal2")
            .claim(
                "amr",
                listOf(
                    mapOf("method" to "password", "timestamp" to baseTime.minusSeconds(300).epochSecond),
                    mapOf("method" to "totp", "timestamp" to baseTime.minusSeconds(30).epochSecond),
                ),
            )
            .build()
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType.JWT).build(),
            claims,
        )
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    @Test
    fun `ClientIpResolver blocks untrusted X-Forwarded-For and Forwarded spoofing and resolves rightmost untrusted hop for trusted proxies`() {
        // 1. Default (no trusted proxies): ignores XFF and Forwarded completely
        val untrustedResolver = ClientIpResolver()
        assertEquals(
            "198.51.100.42",
            untrustedResolver.resolve(
                peerAddress = "198.51.100.42",
                xForwardedForHeaders = listOf("203.0.113.1, 203.0.113.2"),
                forwardedHeaders = listOf("""for="203.0.113.9""""),
            ),
        )

        // 2. Explicit trusted proxies: rejects spoofed left-side XFF, extracts rightmost untrusted IP
        val trustedResolver = ClientIpResolver(setOf("10.0.0.5", "10.0.0.6"))
        assertEquals(
            "198.51.100.7",
            trustedResolver.resolve(
                peerAddress = "10.0.0.5",
                xForwardedForHeaders = listOf("1.1.1.1, 198.51.100.7, 10.0.0.6"),
            ),
        )
        // RFC 7239 Forwarded header right-to-left
        assertEquals(
            "198.51.100.8",
            trustedResolver.resolve(
                peerAddress = "10.0.0.5",
                forwardedHeaders = listOf("""for=1.2.3.4, for="198.51.100.8:54321", for=10.0.0.6"""),
            ),
        )
        // Untrusted peer still cannot spoof even when trustedProxies is non-empty!
        assertEquals(
            "203.0.113.55",
            trustedResolver.resolve(
                peerAddress = "203.0.113.55",
                xForwardedForHeaders = listOf("198.51.100.99"),
            ),
        )

        // 3. AppConfig rejects wildcards, hostnames, and 0.0.0.0 in NEXORA_TRUSTED_PROXIES
        for (bad in listOf("*", "proxy.internal", "0.0.0.0", "::", "10.0.0.1/24")) {
            assertFailsWith<IllegalArgumentException>("Must reject '$bad'") {
                AppConfig.fromEnv(mapOf("NEXORA_ENV" to "local", "NEXORA_TRUSTED_PROXIES" to bad))
            }
        }
        val goodCfg = AppConfig.fromEnv(mapOf("NEXORA_ENV" to "local", "NEXORA_TRUSTED_PROXIES" to "10.0.0.5, 127.0.0.1"))
        assertEquals(setOf("10.0.0.5", "127.0.0.1"), goodCfg.trustedProxies)
    }

    @Test
    fun `blocks header smuggling, oversized bodies, header rotation, and prevents rate-limit audit DB flooding`() = testApplication {
        val key = testKey()
        val verifier = testVerifier(key)
        val validToken = signValidGlobalAdminToken(key)
        val rateAuditCalls = AtomicInteger(0)

        val fakeRepo = object : PlatformCompanyRepository {
            override suspend fun createCompanyWithInitialAdmin(
                actorUserId: UUID, idempotencyKey: String, requestHash: String,
                slug: String, name: String, initialAdminUserId: UUID, correlationId: String?,
            ): PlatformCompanyCreationResult = error("should not reach DB in adversarial negative test")

            override suspend fun recordPlatformAuditEvent(
                actorUserId: UUID?, action: String, targetType: String,
                targetId: String, outcome: String, correlationId: String?, metadataJson: String,
            ) {
                if (action == "platform.company_create_rate_limited") {
                    rateAuditCalls.incrementAndGet()
                }
            }
        }
        val authorizer = DefaultGlobalAdminAuthorizer(
            config = GlobalAdminPolicyConfig(
                globalAdminUserId = globalAdminId,
                expectedIssuer = issuer,
                mfaMaxAge = Duration.ofSeconds(300),
                clockSkew = Duration.ofSeconds(30),
                requireVerifiedMfaFactorInSessionCheck = true,
            ),
            accessTokenVerifier = verifier,
            sessionVerifier = AuthSessionVerifier {
                AuthSessionState.Active(globalAdminId, confirmed = true, active = true, anonymous = false, setOf("totp"))
            },
            clock = fixedClock,
        )

        application {
            nexoraSaasModule(
                config = AppConfig.fromEnv(
                    mapOf(
                        "NEXORA_ENV" to "test",
                        "NEXORA_AUTH_ISSUER" to issuer,
                        "NEXORA_AUTH_JWKS_URL" to "$issuer/.well-known/jwks.json",
                    ),
                ),
                accessTokenVerifier = verifier,
                globalAdminAuthorizer = authorizer,
                platformCompanyService = PlatformCompanyService(fakeRepo),
                platformRateLimiter = PlatformRateLimiter(
                    maxRequestsPerCaller = 4,
                    maxGlobalRequests = 10,
                    window = Duration.ofSeconds(60),
                    clock = fixedClock,
                ),
            )
        }

        // 1. Duplicate Authorization headers -> 401 invalid_token (smuggling blocked)
        val dupAuth = client.post("/api/v1/platform/companies") {
            headers.append(HttpHeaders.Authorization, "Bearer $validToken")
            headers.append(HttpHeaders.Authorization, "Bearer forged-second-token")
            header("Idempotency-Key", "k-dup-auth")
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"co-smug","name":"Smug","initialAdminUserId":"$initialAdminId"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, dupAuth.status)

        // 2. Duplicate Idempotency-Key headers -> 400 validation_failed
        val dupIdem = client.post("/api/v1/platform/companies") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            headers.append("Idempotency-Key", "k-one")
            headers.append("Idempotency-Key", "k-two")
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"co-dup-idem","name":"Dup","initialAdminUserId":"$initialAdminId"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, dupIdem.status)

        // 3. Oversized JSON payload (> 8 KiB) -> 413 payload_too_large
        val hugeName = "A".repeat(9_000)
        val hugeBody = client.post("/api/v1/platform/companies") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header("Idempotency-Key", "k-huge")
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"co-huge","name":"$hugeName","initialAdminUserId":"$initialAdminId"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, hugeBody.status)
        assertEquals("payload_too_large", json.readTree(hugeBody.bodyAsText())["error"]["code"].asText())

        // 4. Consume 4th slot, then flood 15 more requests rotating Authorization, X-Forwarded-For, Forwarded, Idempotency-Key:
        //    ALL 15 must return 429, and DB audit write MUST be deduplicated to EXACTLY 1 call (anti-pool-exhaustion)!
        client.post("/api/v1/platform/companies") {
            header(HttpHeaders.Authorization, "Bearer fake-slot-4")
            header("Idempotency-Key", "k-slot-4")
        }
        repeat(15) { idx ->
            val resp = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer fake-flood-$idx")
                header("X-Forwarded-For", "198.51.100.$idx")
                header("Forwarded", "for=203.0.113.$idx")
                header("Idempotency-Key", "k-flood-$idx")
            }
            assertEquals(HttpStatusCode.TooManyRequests, resp.status)
        }
        assertEquals(1, rateAuditCalls.get(), "Rate-limit audit must be deduplicated to 1 write per source per window")
    }

    @Test
    fun `real Postgres V8 catalog audit, audit-control hardening, and 4-way mixed concurrency without deadlocks`(): Unit = runBlocking {
        val enabled = System.getProperty("nexora.test.localPostgres", "false").equals("true", ignoreCase = true)
        Assume.assumeTrue("Set -Dnexora.test.localPostgres=true to run adversarial Postgres tests", enabled)
        val platformPw = System.getenv("NEXORA_PLATFORM_PASSWORD")
        Assume.assumeTrue("NEXORA_PLATFORM_PASSWORD is required", !platformPw.isNullOrEmpty())

        val jdbcUrl = System.getProperty("nexora.test.localPostgresUrl", "jdbc:postgresql://127.0.0.1:55422/postgres")
        val authBaseUrl = System.getProperty("nexora.test.localAuthUrl", "http://127.0.0.1:55421").trimEnd('/')
        val migratorPw = (System.getenv("FLYWAY_PASSWORD") ?: System.getenv("NEXORA_MIGRATION_PASSWORD"))
            ?: error("FLYWAY_PASSWORD or NEXORA_MIGRATION_PASSWORD is required")

        val migrator = FlywayMigrator(
            FlywayConfig(jdbcUrl = jdbcUrl, username = "app_migrator", password = migratorPw, schema = "nexora", sslMode = "prefer"),
        )
        migrator.migrate()
        assertTrue(migrator.validate().validationSuccessful)

        fun openMigrator(): Connection = DriverManager.getConnection(
            jdbcUrl,
            Properties().apply {
                setProperty("user", "app_migrator")
                setProperty("password", migratorPw)
                setProperty("sslmode", "prefer")
            },
        )

        // 1. Exhaustive catalog audit of ALL functions in `nexora` and `nexora_platform`:
        //    - Every function owned by `app_migrator`
        //    - Every function pins `search_path=pg_catalog`
        //    - ZERO functions executable by PUBLIC, anon, or authenticated
        //    - ZERO `nexora_platform` functions executable by `app_runtime`
        //    - ZERO `nexora` functions executable by `app_platform`
        openMigrator().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT n.nspname,
                           p.proname,
                           pg_get_userbyid(p.proowner) AS owner,
                           COALESCE(array_to_string(p.proconfig, ','), '') AS cfg,
                           has_function_privilege('public', p.oid, 'EXECUTE') AS pub_exec,
                           has_function_privilege('app_runtime', p.oid, 'EXECUTE') AS runtime_exec,
                           has_function_privilege('app_platform', p.oid, 'EXECUTE') AS platform_exec
                    FROM pg_proc p
                    JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname IN ('nexora', 'nexora_platform')
                    ORDER BY n.nspname, p.proname
                    """.trimIndent(),
                ).use { rs ->
                    var count = 0
                    while (rs.next()) {
                        count++
                        val schema = rs.getString(1)
                        val name = rs.getString(2)
                        assertEquals("app_migrator", rs.getString(3), "Owner mismatch on $schema.$name")
                        assertTrue(rs.getString(4).contains("search_path=pg_catalog"), "Missing search_path=pg_catalog on $schema.$name")
                        assertFalse(rs.getBoolean(5), "PUBLIC must never have EXECUTE on $schema.$name")
                        if (schema == "nexora_platform") {
                            assertFalse(rs.getBoolean(6), "app_runtime must never have EXECUTE on $schema.$name")
                        } else {
                            assertFalse(rs.getBoolean(7), "app_platform must never have EXECUTE on $schema.$name")
                        }
                    }
                    assertTrue(count >= 13, "Expected all V1-V8 functions to be audited, got $count")
                }
            }
        }

        // 2. V8 hardening checks on `record_platform_audit_event` as `app_platform`:
        //    - Invalid target_type -> 42501
        //    - Non-object JSONB metadata (`[]`) -> 42501
        //    - Oversized metadata (> 4096 bytes) -> 42501
        //    - 65-char correlation_id with space at char 64 -> safely trimmed and accepted (no 23514!)
        val corrTag = "adv-${UUID.randomUUID().toString().take(8)}"
        val boundaryCorrId = corrTag + "a".repeat(63 - corrTag.length) + " z"
        PostgresPlatformPool.create(
            PlatformDatabaseConfig(jdbcUrl = jdbcUrl, username = "app_platform", password = platformPw, maxPoolSize = 4, minIdle = 1),
        ).use { platformPool ->
            platformPool.dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val badCalls = listOf(
                        "SELECT nexora_platform.record_platform_audit_event(null, 'platform.company_create_denied', 'arbitrary_table', 'x', 'denied', '$corrTag', '{}'::jsonb)",
                        "SELECT nexora_platform.record_platform_audit_event(null, 'platform.company_create_denied', 'platform_companies', 'x', 'denied', '$corrTag', '[]'::jsonb)",
                        "SELECT nexora_platform.record_platform_audit_event(null, 'platform.company_create_denied', 'platform_companies', 'x', 'denied', '$corrTag', jsonb_build_object('pad', repeat('x', 5000)))",
                    )
                    for (sql in badCalls) {
                        val sp = conn.setSavepoint("sp_v8")
                        val err = assertFailsWith<PSQLException>("Expected 42501 for: $sql") {
                            conn.createStatement().use { it.execute(sql) }
                        }
                        assertEquals("42501", err.sqlState)
                        conn.rollback(sp)
                    }

                    // Boundary correlation_id with space at position 64 succeeds cleanly (V8 fix!)
                    conn.prepareStatement(
                        "SELECT nexora_platform.record_platform_audit_event(null, 'platform.company_create_denied', 'platform_companies', 'create', 'denied', ?, '{}'::jsonb)",
                    ).use { ps ->
                        ps.setString(1, boundaryCorrId)
                        ps.executeQuery().use { rs -> assertTrue(rs.next()) }
                    }
                    conn.rollback()
                } finally {
                    runCatching { conn.rollback() }
                }
            }

            // 3. 4-way mixed concurrency stress test (real Postgres, same initialAdminUserId across 4 parallel calls):
            //    2 distinct slugs + 1 colliding slug + 1 colliding Idempotency-Key -> ZERO deadlocks, exact results!
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val authHealthy = runCatching {
                http.send(
                    HttpRequest.newBuilder(URI("$authBaseUrl/health")).GET().timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode() == 200
            }.getOrDefault(false)
            Assume.assumeTrue("Local Supabase Auth must be healthy for 4-way concurrency test", authHealthy)

            val email = "adv-conc-${UUID.randomUUID().toString().take(8)}@nexora.local"
            val pw = "Adv-${UUID.randomUUID().toString().replace("-", "")}!A1"
            val signup = http.send(
                HttpRequest.newBuilder(URI("$authBaseUrl/signup"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"email":"$email","password":"$pw"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, signup.statusCode())
            val initAdminId = UUID.fromString(json.readTree(signup.body())["user"]["id"].asText())
            val actorId = UUID.randomUUID()
            val slugPrefix = "adv-${UUID.randomUUID().toString().take(8)}"
            val createdIds = mutableListOf<UUID>()

            openMigrator().use { conn ->
                conn.prepareStatement("INSERT INTO nexora.profiles (id, display_name) VALUES (?::uuid, 'Adv Admin')").use { ps ->
                    ps.setString(1, initAdminId.toString())
                    ps.executeUpdate()
                }
            }

            try {
                val repo = PostgresPlatformCompanyRepository(platformPool)
                val service = PlatformCompanyService(repo)
                val gaPrincipal = com.nexora.backend.domain.auth.GlobalAdminPrincipal(
                    userId = actorId,
                    issuer = authBaseUrl,
                    sessionId = UUID.randomUUID(),
                    aal = "aal2",
                    mfaMethod = "totp",
                    mfaVerifiedAt = Instant.now(),
                    authorizedAt = Instant.now(),
                )

                val outcomes = coroutineScope {
                    listOf(
                        async {
                            runCatching {
                                service.createCompany(
                                    gaPrincipal,
                                    com.nexora.backend.application.platform.CreateCompanyCommand("$slugPrefix-one", "Co One", initAdminId, "k-$slugPrefix-1"),
                                    "req-$slugPrefix-1",
                                )
                            }
                        },
                        async {
                            runCatching {
                                service.createCompany(
                                    gaPrincipal,
                                    com.nexora.backend.application.platform.CreateCompanyCommand("$slugPrefix-one", "Co One", initAdminId, "k-$slugPrefix-1"),
                                    "req-$slugPrefix-1b",
                                )
                            }
                        },
                        async {
                            runCatching {
                                service.createCompany(
                                    gaPrincipal,
                                    com.nexora.backend.application.platform.CreateCompanyCommand("$slugPrefix-two", "Co Two", initAdminId, "k-$slugPrefix-2"),
                                    "req-$slugPrefix-2",
                                )
                            }
                        },
                        async {
                            runCatching {
                                service.createCompany(
                                    gaPrincipal,
                                    com.nexora.backend.application.platform.CreateCompanyCommand("$slugPrefix-two", "Co Two Dup", initAdminId, "k-$slugPrefix-3"),
                                    "req-$slugPrefix-3",
                                )
                            }
                        },
                    ).awaitAll()
                }

                // Pair 1 (same key k-1): both succeed (one created, one replayed) with identical company ID
                val r1a = outcomes[0].getOrThrow()
                val r1b = outcomes[1].getOrThrow()
                assertEquals(r1a.company.id, r1b.company.id)
                assertEquals(setOf(false, true), setOf(r1a.idempotencyReplayed, r1b.idempotencyReplayed))
                createdIds.add(r1a.company.id)

                // Pair 2 (same slug, different keys k-2 & k-3): exactly 1 succeeds and 1 fails with company_slug_conflict (ZERO deadlocks!)
                val pair2Successes = listOf(outcomes[2], outcomes[3]).mapNotNull { it.getOrNull() }
                val pair2Failures = listOf(outcomes[2], outcomes[3]).mapNotNull { it.exceptionOrNull() }
                assertEquals(1, pair2Successes.size)
                assertEquals(1, pair2Failures.size)
                assertEquals(
                    "company_slug_conflict",
                    (pair2Failures.single() as com.nexora.backend.application.multitenant.DomainConflictException).code,
                )
                createdIds.add(pair2Successes.single().company.id)

                // Verify exact 1:1 audit events for created companies (zero duplicates)
                openMigrator().use { conn ->
                    for (cid in createdIds) {
                        conn.prepareStatement(
                            "SELECT count(*) FROM nexora.audit_events WHERE company_id = ?::uuid AND action = 'platform.company_created'",
                        ).use { ps ->
                            ps.setString(1, cid.toString())
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                assertEquals(1, rs.getInt(1))
                            }
                        }
                    }
                }
            } finally {
                openMigrator().use { conn ->
                    conn.autoCommit = false
                    try {
                        conn.prepareStatement("DELETE FROM nexora_platform.idempotency_keys WHERE actor_user_id = ?::uuid").use { ps ->
                            ps.setString(1, actorId.toString())
                            ps.executeUpdate()
                        }
                        conn.prepareStatement("DELETE FROM nexora.audit_events WHERE actor_user_id = ?::uuid").use { ps ->
                            ps.setString(1, actorId.toString())
                            ps.executeUpdate()
                        }
                        for (cid in createdIds) {
                            conn.prepareStatement("UPDATE nexora.companies SET status = 'suspended' WHERE id = ?::uuid").use { ps ->
                                ps.setString(1, cid.toString())
                                ps.executeUpdate()
                            }
                            conn.prepareStatement("DELETE FROM nexora.company_memberships WHERE company_id = ?::uuid").use { ps ->
                                ps.setString(1, cid.toString())
                                ps.executeUpdate()
                            }
                            conn.prepareStatement("DELETE FROM nexora.companies WHERE id = ?::uuid").use { ps ->
                                ps.setString(1, cid.toString())
                                ps.executeUpdate()
                            }
                        }
                        conn.prepareStatement("DELETE FROM nexora.profiles WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, initAdminId.toString())
                            ps.executeUpdate()
                        }
                        conn.commit()
                    } catch (_: Throwable) {
                        runCatching { conn.rollback() }
                    }
                }
            }
        }
    }
}
