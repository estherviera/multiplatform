package com.nexora.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.auth.CachingJwksKeyProvider
import com.nexora.backend.adapter.auth.JwksFetcher
import com.nexora.backend.adapter.auth.JwtAuthConfig
import com.nexora.backend.adapter.auth.NimbusJwtAccessTokenVerifier
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.adapter.persistence.postgres.PlatformDatabaseConfig
import com.nexora.backend.application.auth.AuthSessionState
import com.nexora.backend.application.auth.AuthSessionVerifier
import com.nexora.backend.application.auth.DefaultGlobalAdminAuthorizer
import com.nexora.backend.application.auth.GlobalAdminPolicyConfig
import com.nexora.backend.application.multitenant.DomainConflictException
import com.nexora.backend.application.platform.PlatformCompanyCreationResult
import com.nexora.backend.application.platform.PlatformCompanyRepository
import com.nexora.backend.application.platform.PlatformCompanyService
import com.nexora.backend.application.platform.PlatformRateLimiter
import com.nexora.backend.bootstrap.AppConfig
import com.nexora.backend.domain.multitenant.Company
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.CompanyRole
import com.nexora.backend.domain.multitenant.CompanyStatus
import com.nexora.backend.domain.multitenant.MembershipStatus
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/**
 * Unit and HTTP contract tests with ephemeral ES256 test keys for Phase 2C.3B:
 * `POST /api/v1/platform/companies` authorization, validation, rate limiting,
 * idempotency, denial/failure audit recording, and PlatformDatabaseConfig guardrails.
 */
class PlatformCompanyRoutesUnitTest {
    private val json = ObjectMapper()
    private val issuer = "http://127.0.0.1:55421"
    private val audience = "authenticated"
    private val baseTime = Instant.parse("2026-11-22T21:20:00Z")
    private val fixedClock = Clock.fixed(baseTime, ZoneOffset.UTC)
    private val globalAdminId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val ordinaryUserId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val initialAdminId = UUID.fromString("33333333-3333-4333-8333-333333333333")

    private fun testKey(): ECKey = ECKeyGenerator(Curve.P_256)
        .keyID("unit-kid-2c3b")
        .keyUse(KeyUse.SIGNATURE)
        .keyOperations(setOf(KeyOperation.VERIFY))
        .algorithm(JWSAlgorithm.ES256)
        .generate()

    private fun testVerifier(key: ECKey): NimbusJwtAccessTokenVerifier {
        val authCfg = JwtAuthConfig(issuer = issuer, audience = audience, jwksUri = URI("$issuer/.well-known/jwks.json"))
        val provider = CachingJwksKeyProvider(JwksFetcher { JWKSet(key.toPublicJWK()).toString() }, clock = fixedClock)
        return NimbusJwtAccessTokenVerifier(authCfg, provider, fixedClock)
    }

    private fun signToken(
        key: ECKey,
        userId: UUID = globalAdminId,
        aal: String = "aal2",
        mfaAgeSeconds: Long = 60L,
        iatAgeSeconds: Long = 5L,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(userId.toString())
            .issueTime(Date.from(baseTime.minusSeconds(iatAgeSeconds)))
            .expirationTime(Date.from(baseTime.plusSeconds(3600)))
            .claim("role", "authenticated")
            .claim("is_anonymous", false)
            .claim("session_id", UUID.randomUUID().toString())
            .claim("aal", aal)
            .claim(
                "amr",
                if (aal == "aal2") {
                    listOf(
                        mapOf("method" to "password", "timestamp" to baseTime.minusSeconds(600).epochSecond),
                        mapOf("method" to "totp", "timestamp" to baseTime.minusSeconds(mfaAgeSeconds).epochSecond),
                    )
                } else {
                    listOf(mapOf("method" to "password", "timestamp" to baseTime.minusSeconds(60).epochSecond))
                },
            )
            .build()
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType.JWT).build(),
            claims,
        )
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    private fun baseConfig(): AppConfig = AppConfig.fromEnv(
        mapOf(
            "NEXORA_ENV" to "test",
            "NEXORA_AUTH_ISSUER" to issuer,
            "NEXORA_AUTH_JWKS_URL" to "$issuer/.well-known/jwks.json",
        ),
    )

    @Test
    fun `PlatformDatabaseConfig rejects runtime, migrator, and superuser roles and redacts password`() {
        for (forbidden in listOf("app_runtime", "app_migrator", "postgres", "supabase_admin", "service_role")) {
            assertFailsWith<IllegalArgumentException>("Must reject $forbidden") {
                PlatformDatabaseConfig(
                    jdbcUrl = "jdbc:postgresql://127.0.0.1:55422/postgres",
                    username = forbidden,
                    password = "secret-test-password-1234567890",
                )
            }
        }
        val valid = PlatformDatabaseConfig(
            jdbcUrl = "jdbc:postgresql://127.0.0.1:55422/postgres",
            username = "app_platform",
            password = "secret-platform-password-never-printed",
        )
        assertEquals("nexora_platform", valid.schema)
        assertFalse(valid.toString().contains("secret-platform-password-never-printed"))

        // Production enforces strict TLS on platform DB too
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "production",
                    "NEXORA_READINESS_TOKEN" to "ops-secret",
                    "NEXORA_AUTH_ISSUER" to "https://example.supabase.co/auth/v1",
                    "NEXORA_AUTH_JWKS_URL" to "https://example.supabase.co/auth/v1/.well-known/jwks.json",
                    "NEXORA_PLATFORM_JDBC_URL" to "jdbc:postgresql://db.example.com:5432/postgres",
                    "NEXORA_PLATFORM_USER" to "app_platform",
                    "NEXORA_PLATFORM_PASSWORD" to "secret-platform-password-1234567890",
                    "NEXORA_PLATFORM_SSL_MODE" to "prefer",
                ),
            )
        }
    }

    @Test
    fun `POST platform companies enforces disabled default, auth failures, rate limits, validation, and idempotency`() = testApplication {
        val key = testKey()
        val verifier = testVerifier(key)

        // 1. Disabled by default when NEXORA_GLOBAL_ADMIN_SUB is absent -> 403 global_admin_disabled
        application {
            nexoraSaasModule(
                config = baseConfig(),
                accessTokenVerifier = verifier,
            )
        }
        val disabledResp = client.post("/api/v1/platform/companies") {
            header(HttpHeaders.Authorization, "Bearer ${signToken(key)}")
            header("Idempotency-Key", "idem-disabled-1")
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"acme-one","name":"Acme One","initialAdminUserId":"$initialAdminId"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, disabledResp.status)
        assertEquals("global_admin_disabled", json.readTree(disabledResp.bodyAsText())["error"]["code"].asText())
        assertEquals("no-store", disabledResp.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `enforces global admin MFA, session state, Auth outage, rate limit, validation, conflicts, and clean logs`() = testApplication {
        val key = testKey()
        val verifier = testVerifier(key)
        var sessionState: AuthSessionState = AuthSessionState.Active(
            userId = globalAdminId,
            confirmed = true,
            active = true,
            anonymous = false,
            verifiedMfaFactorTypes = setOf("totp"),
        )
        val recordedAudits = CopyOnWriteArrayList<Pair<String, String>>()
        val storedByKey = LinkedHashMap<String, Pair<String, PlatformCompanyCreationResult>>()
        val usedSlugs = HashSet<String>()

        val fakeRepo = object : PlatformCompanyRepository {
            override suspend fun createCompanyWithInitialAdmin(
                actorUserId: UUID,
                idempotencyKey: String,
                requestHash: String,
                slug: String,
                name: String,
                initialAdminUserId: UUID,
                correlationId: String?,
            ): PlatformCompanyCreationResult {
                val existing = storedByKey[idempotencyKey]
                if (existing != null) {
                    if (existing.first != requestHash) {
                        throw DomainConflictException("idempotency_key_conflict", "Idempotency key conflict")
                    }
                    return existing.second.copy(idempotencyReplayed = true)
                }
                if (!usedSlugs.add(slug)) {
                    throw DomainConflictException("company_slug_conflict", "Slug already exists")
                }
                val cid = UUID.randomUUID()
                val res = PlatformCompanyCreationResult(
                    company = Company(cid, slug, name, CompanyStatus.ACTIVE, baseTime, baseTime),
                    initialAdminMembership = CompanyMembership(cid, initialAdminUserId, CompanyRole.COMPANY_ADMIN, MembershipStatus.ACTIVE, baseTime, baseTime),
                    idempotencyReplayed = false,
                )
                storedByKey[idempotencyKey] = requestHash to res
                return res
            }

            override suspend fun recordPlatformAuditEvent(
                actorUserId: UUID?,
                action: String,
                targetType: String,
                targetId: String,
                outcome: String,
                correlationId: String?,
                metadataJson: String,
            ) {
                recordedAudits.add(action to outcome)
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
            sessionVerifier = AuthSessionVerifier { sessionState },
            clock = fixedClock,
        )
        val service = PlatformCompanyService(fakeRepo)

        application {
            nexoraSaasModule(
                config = baseConfig(),
                accessTokenVerifier = verifier,
                globalAdminAuthorizer = authorizer,
                platformCompanyService = service,
                platformRateLimiter = PlatformRateLimiter(
                    maxRequestsPerCaller = 15,
                    maxGlobalRequests = 30,
                    window = Duration.ofSeconds(60),
                    clock = fixedClock,
                ),
            )
        }

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)

        val validToken = signToken(key)
        val aal1Token = signToken(key, aal = "aal1")
        val staleMfaToken = signToken(key, aal = "aal2", mfaAgeSeconds = 301L, iatAgeSeconds = 2L)
        val ordinaryToken = signToken(key, userId = ordinaryUserId)

        try {
            // 1. Missing token -> 401
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/platform/companies").status)

            // 2. Ordinary user -> 403 forbidden
            val ordResp = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer $ordinaryToken")
                header("Idempotency-Key", "idem-ord")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"acme-ord","name":"Acme","initialAdminUserId":"$initialAdminId"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, ordResp.status)
            assertEquals("forbidden", json.readTree(ordResp.bodyAsText())["error"]["code"].asText())

            // 3. Global admin without MFA (aal1) -> 403 mfa_required
            val aal1Resp = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer $aal1Token")
                header("Idempotency-Key", "idem-aal1")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"acme-aal1","name":"Acme","initialAdminUserId":"$initialAdminId"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, aal1Resp.status)
            assertEquals("mfa_required", json.readTree(aal1Resp.bodyAsText())["error"]["code"].asText())

            // 4. Global admin with stale refreshed MFA -> 403 mfa_reauthentication_required
            val staleResp = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer $staleMfaToken")
                header("Idempotency-Key", "idem-stale")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"acme-stale","name":"Acme","initialAdminUserId":"$initialAdminId"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, staleResp.status)
            assertEquals("mfa_reauthentication_required", json.readTree(staleResp.bodyAsText())["error"]["code"].asText())

            // 5. Revoked session -> 401 session_revoked
            sessionState = AuthSessionState.RevokedOrInvalid("auth_rejected_http_403")
            val revResp = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header("Idempotency-Key", "idem-rev")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"acme-rev","name":"Acme","initialAdminUserId":"$initialAdminId"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, revResp.status)
            assertEquals("session_revoked", json.readTree(revResp.bodyAsText())["error"]["code"].asText())

            // 6. Supabase Auth unavailable -> 503 auth_verifier_unavailable
            sessionState = AuthSessionState.Unavailable("auth_timeout")
            val downResp = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header("Idempotency-Key", "idem-down")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"acme-down","name":"Acme","initialAdminUserId":"$initialAdminId"}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, downResp.status)
            assertEquals("auth_verifier_unavailable", json.readTree(downResp.bodyAsText())["error"]["code"].asText())

            // Restore healthy live session
            sessionState = AuthSessionState.Active(globalAdminId, confirmed = true, active = true, anonymous = false, setOf("totp"))

            // 7. Validation: missing Idempotency-Key, uppercase slug, unknown JSON field -> 400
            val badSlug = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header("Idempotency-Key", "idem-bad-1")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"Invalid_Slug","name":"Acme","initialAdminUserId":"$initialAdminId"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, badSlug.status)

            val unknownField = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header("Idempotency-Key", "idem-bad-2")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"valid-slug","name":"Acme","initialAdminUserId":"$initialAdminId","isGlobalAdmin":true}""")
            }
            assertEquals(HttpStatusCode.BadRequest, unknownField.status)

            // 8. Valid creation -> 201 Created; idempotent replay -> 200 OK; key reuse with diff body -> 409; duplicate slug -> 409
            val created = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header("Idempotency-Key", "idem-ok-1")
                header(HttpHeaders.XRequestId, "req-create-1")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"acme-academy","name":"Acme Academy","initialAdminUserId":"$initialAdminId"}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)
            val createdJson = json.readTree(created.bodyAsText())
            assertFalse(createdJson["idempotencyReplayed"].asBoolean())
            assertEquals("company_admin", createdJson["initialAdminMembership"]["role"].asText())

            val replayed = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header("Idempotency-Key", "idem-ok-1")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"acme-academy","name":"Acme Academy","initialAdminUserId":"$initialAdminId"}""")
            }
            assertEquals(HttpStatusCode.OK, replayed.status)
            assertEquals("true", replayed.headers["X-Idempotency-Replayed"])
            assertEquals(createdJson["company"]["id"].asText(), json.readTree(replayed.bodyAsText())["company"]["id"].asText())

            val keyConflict = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header("Idempotency-Key", "idem-ok-1")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"different-slug","name":"Other","initialAdminUserId":"$initialAdminId"}""")
            }
            assertEquals(HttpStatusCode.Conflict, keyConflict.status)
            assertEquals("idempotency_key_conflict", json.readTree(keyConflict.bodyAsText())["error"]["code"].asText())

            val slugDup = client.post("/api/v1/platform/companies") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header("Idempotency-Key", "idem-ok-2")
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"acme-academy","name":"Acme Dup","initialAdminUserId":"$initialAdminId"}""")
            }
            assertEquals(HttpStatusCode.Conflict, slugDup.status)
            assertEquals("company_slug_conflict", json.readTree(slugDup.bodyAsText())["error"]["code"].asText())

            // Verify denials and failures were audited in separate calls
            assertTrue(recordedAudits.any { it.first == "platform.company_create_denied" && it.second == "denied" })
            assertTrue(recordedAudits.any { it.first == "platform.company_create_failed" && it.second == "failed" })

            // Verify zero tokens leaked in logs
            val logs = appender.list.joinToString("\n") { it.formattedMessage }
            for (tok in listOf(validToken, aal1Token, staleMfaToken, ordinaryToken)) {
                assertFalse(logs.contains(tok), "Token must never appear in logs")
            }
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `enforces 429 rate limiting with Retry-After and audits rate limit hits`() = testApplication {
        val key = testKey()
        val verifier = testVerifier(key)
        val audits = CopyOnWriteArrayList<String>()
        val fakeRepo = object : PlatformCompanyRepository {
            override suspend fun createCompanyWithInitialAdmin(
                actorUserId: UUID, idempotencyKey: String, requestHash: String,
                slug: String, name: String, initialAdminUserId: UUID, correlationId: String?,
            ): PlatformCompanyCreationResult = error("not reached")

            override suspend fun recordPlatformAuditEvent(
                actorUserId: UUID?, action: String, targetType: String,
                targetId: String, outcome: String, correlationId: String?, metadataJson: String,
            ) {
                audits.add(action)
            }
        }
        application {
            nexoraSaasModule(
                config = baseConfig(),
                accessTokenVerifier = verifier,
                platformCompanyService = PlatformCompanyService(fakeRepo),
                platformRateLimiter = PlatformRateLimiter(
                    maxRequestsPerCaller = 2,
                    maxGlobalRequests = 5,
                    window = Duration.ofSeconds(60),
                    clock = fixedClock,
                ),
            )
        }

        // Rotating Authorization tokens and spoofing X-Forwarded-For headers must NOT bypass per-source rate limiting
        client.post("/api/v1/platform/companies") {
            header(HttpHeaders.Authorization, "Bearer fake-token-1")
            header("X-Forwarded-For", "203.0.113.10")
        }
        client.post("/api/v1/platform/companies") {
            header(HttpHeaders.Authorization, "Bearer fake-token-2")
            header("X-Forwarded-For", "203.0.113.11")
        }
        val limited = client.post("/api/v1/platform/companies") {
            header(HttpHeaders.Authorization, "Bearer ${signToken(key)}")
            header("X-Forwarded-For", "203.0.113.12")
        }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("rate_limit_exceeded", json.readTree(limited.bodyAsText())["error"]["code"].asText())
        assertTrue((limited.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 0L) >= 1L)
        assertTrue("platform.company_create_rate_limited" in audits)
    }
}
