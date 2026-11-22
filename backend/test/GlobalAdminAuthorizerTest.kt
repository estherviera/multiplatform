package com.nexora.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.nexora.backend.adapter.auth.CachingJwksKeyProvider
import com.nexora.backend.adapter.auth.GlobalAdminAuthConfig
import com.nexora.backend.adapter.auth.JwksFetcher
import com.nexora.backend.adapter.auth.JwksUnavailableException
import com.nexora.backend.adapter.auth.JwtAuthConfig
import com.nexora.backend.adapter.auth.NimbusJwtAccessTokenVerifier
import com.nexora.backend.adapter.auth.SupabaseAuthSessionVerifier
import com.nexora.backend.application.auth.AuthSessionState
import com.nexora.backend.application.auth.AuthSessionVerifier
import com.nexora.backend.application.auth.DefaultGlobalAdminAuthorizer
import com.nexora.backend.application.auth.GlobalAdminPolicyConfig
import com.nexora.backend.bootstrap.AppConfig
import com.nexora.backend.domain.auth.GlobalAdminAuthorizationResult
import com.nexora.backend.domain.auth.GlobalAdminDenialReason
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Unit & embedded loopback HTTP tests with ephemeral ES256 test keys and a controllable clock
 * for Phase 2C.3A global administrator authorization, live session checking, and MFA freshness.
 */
class GlobalAdminAuthorizerTest {
    private val issuer = "http://127.0.0.1:55421"
    private val audience = "authenticated"
    private val baseTime = Instant.parse("2026-11-22T21:00:00Z")
    private val globalAdminId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val normalUserId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = current
    }

    private fun testEcKey(kid: String = "ga-kid-1"): ECKey = ECKeyGenerator(Curve.P_256)
        .keyID(kid)
        .keyUse(KeyUse.SIGNATURE)
        .keyOperations(setOf(KeyOperation.VERIFY))
        .algorithm(JWSAlgorithm.ES256)
        .generate()

    private fun jwtVerifier(
        key: ECKey,
        clock: Clock,
        jwksFailureReason: String? = null,
    ): NimbusJwtAccessTokenVerifier {
        val cfg = JwtAuthConfig(
            issuer = issuer,
            audience = audience,
            jwksUri = URI("$issuer/.well-known/jwks.json"),
        )
        val provider = CachingJwksKeyProvider(
            fetcher = JwksFetcher {
                if (jwksFailureReason != null) throw JwksUnavailableException(jwksFailureReason)
                JWKSet(key.toPublicJWK()).toString()
            },
            clock = clock,
        )
        return NimbusJwtAccessTokenVerifier(cfg, provider, clock)
    }

    private fun signToken(
        key: ECKey,
        userId: UUID = globalAdminId,
        sessionId: UUID? = UUID.randomUUID(),
        iss: String = issuer,
        aud: String = audience,
        iat: Instant = baseTime,
        exp: Instant = baseTime.plusSeconds(3600),
        aal: String? = "aal2",
        amr: Any? = listOf(
            mapOf("method" to "password", "timestamp" to baseTime.minusSeconds(600).epochSecond),
            mapOf("method" to "totp", "timestamp" to baseTime.minusSeconds(60).epochSecond),
        ),
        role: String = "authenticated",
        extraClaims: Map<String, Any> = emptyMap(),
    ): String {
        val builder = JWTClaimsSet.Builder()
            .issuer(iss)
            .audience(aud)
            .subject(userId.toString())
            .issueTime(Date.from(iat))
            .expirationTime(Date.from(exp))
            .claim("role", role)
            .claim("is_anonymous", false)
        if (sessionId != null) builder.claim("session_id", sessionId.toString())
        if (aal != null) builder.claim("aal", aal)
        if (amr != null) builder.claim("amr", amr)
        extraClaims.forEach { (k, v) -> builder.claim(k, v) }

        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType.JWT).build(),
            builder.build(),
        )
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    private fun policyConfig(
        adminId: UUID? = globalAdminId,
        mfaMaxAgeSeconds: Long = 300L,
    ) = GlobalAdminPolicyConfig(
        globalAdminUserId = adminId,
        expectedIssuer = if (adminId != null) issuer else null,
        mfaMaxAge = Duration.ofSeconds(mfaMaxAgeSeconds),
        clockSkew = Duration.ofSeconds(30),
        requireVerifiedMfaFactorInSessionCheck = true,
    )

    @Test
    fun `disabled by default when NEXORA_GLOBAL_ADMIN_SUB is absent and rejects invalid server configs`(): Unit = runBlocking {
        // 1. Absent NEXORA_GLOBAL_ADMIN_SUB -> disabled by default
        val defaultCfg = AppConfig.fromEnv(
            mapOf(
                "NEXORA_ENV" to "local",
                "NEXORA_AUTH_ISSUER" to issuer,
                "NEXORA_AUTH_JWKS_URL" to "$issuer/.well-known/jwks.json",
            ),
        )
        assertNull(defaultCfg.globalAdmin)
        assertTrue(defaultCfg.toString().contains("globalAdminConfigured=false"))

        val key = testEcKey()
        val clock = MutableClock(baseTime)
        val sessionCalls = AtomicInteger(0)
        val disabledAuthorizer = DefaultGlobalAdminAuthorizer(
            config = GlobalAdminPolicyConfig.disabled(),
            accessTokenVerifier = jwtVerifier(key, clock),
            sessionVerifier = AuthSessionVerifier {
                sessionCalls.incrementAndGet()
                AuthSessionState.Active(globalAdminId, confirmed = true, active = true, anonymous = false, setOf("totp"))
            },
            clock = clock,
        )
        val res = assertIs<GlobalAdminAuthorizationResult.Denied>(
            disabledAuthorizer.authorize(signToken(key), correlationId = "req-disabled"),
        )
        assertEquals(GlobalAdminDenialReason.GLOBAL_ADMIN_DISABLED, res.reason)
        assertEquals(0, sessionCalls.get(), "Disabled global admin must not contact Auth")

        // 2. Malformed UUID -> fail fast
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "local",
                    "NEXORA_AUTH_ISSUER" to issuer,
                    "NEXORA_AUTH_JWKS_URL" to "$issuer/.well-known/jwks.json",
                    "NEXORA_GLOBAL_ADMIN_SUB" to "not-a-valid-uuid",
                ),
            )
        }

        // 3. Multiple comma-separated UUIDs -> fail fast (strictly single UUID)
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "local",
                    "NEXORA_AUTH_ISSUER" to issuer,
                    "NEXORA_AUTH_JWKS_URL" to "$issuer/.well-known/jwks.json",
                    "NEXORA_GLOBAL_ADMIN_SUB" to "$globalAdminId,$normalUserId",
                ),
            )
        }

        // 4. Global admin configured without NEXORA_AUTH_ISSUER -> fail fast
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "local",
                    "NEXORA_GLOBAL_ADMIN_SUB" to globalAdminId.toString(),
                ),
            )
        }

        // 5. service_role JWT accidentally passed as NEXORA_AUTH_API_KEY -> fail fast
        val fakeServiceRoleJwt =
            Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray()) + "." +
                Base64.getUrlEncoder().withoutPadding().encodeToString("""{"role":"service_role","iss":"supabase"}""".toByteArray()) + ".sig"
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "local",
                    "NEXORA_AUTH_ISSUER" to issuer,
                    "NEXORA_AUTH_JWKS_URL" to "$issuer/.well-known/jwks.json",
                    "NEXORA_GLOBAL_ADMIN_SUB" to globalAdminId.toString(),
                    "NEXORA_AUTH_API_KEY" to fakeServiceRoleJwt,
                ),
            )
        }

        // 6. Out-of-bounds MFA max age (> 900s or < 30s) -> fail fast
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "local",
                    "NEXORA_AUTH_ISSUER" to issuer,
                    "NEXORA_AUTH_JWKS_URL" to "$issuer/.well-known/jwks.json",
                    "NEXORA_GLOBAL_ADMIN_SUB" to globalAdminId.toString(),
                    "NEXORA_GLOBAL_ADMIN_MFA_MAX_AGE_SECONDS" to "3600",
                ),
            )
        }

        // 7. Valid global admin config redacts optional public apiKey in toString()
        val validCfg = AppConfig.fromEnv(
            mapOf(
                "NEXORA_ENV" to "production",
                "NEXORA_READINESS_TOKEN" to "ops-secret",
                "NEXORA_AUTH_ISSUER" to "https://example.supabase.co/auth/v1",
                "NEXORA_AUTH_JWKS_URL" to "https://example.supabase.co/auth/v1/.well-known/jwks.json",
                "NEXORA_GLOBAL_ADMIN_SUB" to globalAdminId.toString(),
                "NEXORA_GLOBAL_ADMIN_MFA_MAX_AGE_SECONDS" to "300",
                "NEXORA_AUTH_API_KEY" to "sb_publishable_demo_key_not_logged",
            ),
        )
        assertEquals(globalAdminId, validCfg.globalAdmin?.globalAdminUserId)
        assertEquals(URI("https://example.supabase.co/auth/v1/user"), validCfg.globalAdmin?.authUserUri)
        assertFalse(validCfg.globalAdmin.toString().contains("sb_publishable_demo_key_not_logged"))
        assertFalse(validCfg.toString().contains(globalAdminId.toString()))
    }

    @Test
    fun `rejects normal user, forged or expired tokens, insufficient MFA, stale refreshed MFA, and revoked sessions`(): Unit = runBlocking {
        val key = testEcKey()
        val otherKey = testEcKey("ga-kid-1")
        val clock = MutableClock(baseTime)
        val sessionCalls = AtomicInteger(0)
        var nextSessionState: AuthSessionState = AuthSessionState.Active(
            userId = globalAdminId,
            confirmed = true,
            active = true,
            anonymous = false,
            verifiedMfaFactorTypes = setOf("totp"),
        )
        val authorizer = DefaultGlobalAdminAuthorizer(
            config = policyConfig(),
            accessTokenVerifier = jwtVerifier(key, clock),
            sessionVerifier = AuthSessionVerifier {
                sessionCalls.incrementAndGet()
                nextSessionState
            },
            clock = clock,
        )

        // 1. Normal authenticated user (even with aal2, fresh totp amr, and spoofed user_metadata) -> NOT_GLOBAL_ADMIN, 0 /user calls
        val normalToken = signToken(
            key = key,
            userId = normalUserId,
            extraClaims = mapOf(
                "user_metadata" to mapOf("role" to "global_admin", "is_global_admin" to true),
                "app_metadata" to mapOf("role" to "global_admin"),
            ),
        )
        assertEquals(
            GlobalAdminDenialReason.NOT_GLOBAL_ADMIN,
            assertIs<GlobalAdminAuthorizationResult.Denied>(authorizer.authorize(normalToken)).reason,
        )
        assertEquals(0, sessionCalls.get(), "Normal users must never trigger outbound Auth session verification")

        // 2. Forged signature, expired token, HS256, alg=none, service_role, malformed amr -> INVALID_TOKEN / TOKEN_EXPIRED
        assertEquals(
            GlobalAdminDenialReason.INVALID_TOKEN,
            assertIs<GlobalAdminAuthorizationResult.Denied>(authorizer.authorize(signToken(otherKey))).reason,
        )
        assertEquals(
            GlobalAdminDenialReason.TOKEN_EXPIRED,
            assertIs<GlobalAdminAuthorizationResult.Denied>(
                authorizer.authorize(signToken(key, exp = baseTime.minusSeconds(60))),
            ).reason,
        )
        val hsSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hsJwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.HS256).keyID("ga-kid-1").build(),
            JWTClaimsSet.Builder().subject(globalAdminId.toString()).issuer(issuer).audience(audience)
                .expirationTime(Date.from(baseTime.plusSeconds(300))).claim("role", "authenticated").build(),
        ).apply { sign(MACSigner(hsSecret)) }.serialize()
        assertEquals(
            GlobalAdminDenialReason.INVALID_TOKEN,
            assertIs<GlobalAdminAuthorizationResult.Denied>(authorizer.authorize(hsJwt)).reason,
        )
        val plainJwt = PlainJWT(
            JWTClaimsSet.Builder().subject(globalAdminId.toString()).issuer(issuer).audience(audience)
                .expirationTime(Date.from(baseTime.plusSeconds(300))).claim("role", "authenticated").build(),
        ).serialize()
        assertEquals(
            GlobalAdminDenialReason.INVALID_TOKEN,
            assertIs<GlobalAdminAuthorizationResult.Denied>(authorizer.authorize(plainJwt)).reason,
        )
        assertEquals(
            GlobalAdminDenialReason.INVALID_TOKEN,
            assertIs<GlobalAdminAuthorizationResult.Denied>(
                authorizer.authorize(signToken(key, role = "service_role")),
            ).reason,
        )
        assertEquals(
            GlobalAdminDenialReason.INVALID_TOKEN,
            assertIs<GlobalAdminAuthorizationResult.Denied>(
                authorizer.authorize(signToken(key, amr = "not-a-list")),
            ).reason,
        )

        // 3. Global admin missing session_id -> MISSING_SESSION_ID
        assertEquals(
            GlobalAdminDenialReason.MISSING_SESSION_ID,
            assertIs<GlobalAdminAuthorizationResult.Denied>(
                authorizer.authorize(signToken(key, sessionId = null)),
            ).reason,
        )

        // 4. Global admin with valid JWT but MFA insufficient (aal1, missing amr, or password-only amr)
        assertEquals(
            GlobalAdminDenialReason.MFA_REQUIRED,
            assertIs<GlobalAdminAuthorizationResult.Denied>(
                authorizer.authorize(signToken(key, aal = "aal1")),
            ).reason,
        )
        assertEquals(
            GlobalAdminDenialReason.MFA_EVIDENCE_MISSING,
            assertIs<GlobalAdminAuthorizationResult.Denied>(
                authorizer.authorize(signToken(key, aal = "aal2", amr = emptyList<Any>())),
            ).reason,
        )
        assertEquals(
            GlobalAdminDenialReason.MFA_EVIDENCE_MISSING,
            assertIs<GlobalAdminAuthorizationResult.Denied>(
                authorizer.authorize(
                    signToken(
                        key,
                        aal = "aal2",
                        amr = listOf(mapOf("method" to "password", "timestamp" to baseTime.minusSeconds(10).epochSecond)),
                    ),
                ),
            ).reason,
        )

        // 5. Global admin with refreshed JWT (fresh iat=2s ago) but STALE MFA challenge (301s ago > 300s window) -> MFA_STEP_UP_EXPIRED
        val refreshedStaleMfaToken = signToken(
            key = key,
            iat = baseTime.minusSeconds(2),
            exp = baseTime.plusSeconds(3598),
            aal = "aal2",
            amr = listOf(
                mapOf("method" to "password", "timestamp" to baseTime.minusSeconds(1800).epochSecond),
                mapOf("method" to "totp", "timestamp" to baseTime.minusSeconds(301).epochSecond),
            ),
        )
        assertEquals(
            GlobalAdminDenialReason.MFA_STEP_UP_EXPIRED,
            assertIs<GlobalAdminAuthorizationResult.Denied>(authorizer.authorize(refreshedStaleMfaToken)).reason,
        )
        assertEquals(0, sessionCalls.get(), "Failed JWT/MFA checks must short-circuit before calling Auth /user")

        // 6. Global admin with valid fresh MFA JWT, but live session REVOKED -> SESSION_REVOKED_OR_INVALID
        nextSessionState = AuthSessionState.RevokedOrInvalid("auth_rejected_http_403")
        assertEquals(
            GlobalAdminDenialReason.SESSION_REVOKED_OR_INVALID,
            assertIs<GlobalAdminAuthorizationResult.Denied>(authorizer.authorize(signToken(key))).reason,
        )

        // 7. Global admin with valid fresh MFA JWT, but live account inactive/unconfirmed -> ACCOUNT_NOT_ACTIVE_OR_UNCONFIRMED
        nextSessionState = AuthSessionState.Active(globalAdminId, confirmed = true, active = false, anonymous = false, setOf("totp"))
        assertEquals(
            GlobalAdminDenialReason.ACCOUNT_NOT_ACTIVE_OR_UNCONFIRMED,
            assertIs<GlobalAdminAuthorizationResult.Denied>(authorizer.authorize(signToken(key))).reason,
        )

        // 8. Global admin with valid fresh MFA JWT, but MFA factor was unenrolled on Auth -> MFA_FACTOR_NOT_VERIFIED
        nextSessionState = AuthSessionState.Active(globalAdminId, confirmed = true, active = true, anonymous = false, emptySet())
        assertEquals(
            GlobalAdminDenialReason.MFA_FACTOR_NOT_VERIFIED,
            assertIs<GlobalAdminAuthorizationResult.Denied>(authorizer.authorize(signToken(key))).reason,
        )

        // 9. JWKS unavailable -> Unavailable (fail closed)
        val unavailableJwksAuthorizer = DefaultGlobalAdminAuthorizer(
            config = policyConfig(),
            accessTokenVerifier = jwtVerifier(key, clock, jwksFailureReason = "jwks_timeout"),
            sessionVerifier = AuthSessionVerifier { error("must not reach session verifier") },
            clock = clock,
        )
        val unavailableRes = unavailableJwksAuthorizer.authorize(signToken(key))
        assertIs<GlobalAdminAuthorizationResult.Unavailable>(unavailableRes)
    }

    @Test
    fun `authorizes valid global admin over real HTTP session verifier, fails closed on Auth outages, and never logs tokens`(): Unit = runBlocking {
        val key = testEcKey()
        val clock = MutableClock(baseTime)
        val sessionId = UUID.randomUUID()
        val mfaVerifiedAt = baseTime.minusSeconds(120)
        val validToken = signToken(
            key = key,
            userId = globalAdminId,
            sessionId = sessionId,
            aal = "aal2",
            amr = listOf(
                mapOf("method" to "password", "timestamp" to baseTime.minusSeconds(600).epochSecond),
                mapOf("method" to "totp", "timestamp" to mfaVerifiedAt.epochSecond),
            ),
        )
        val publicApiKey = "sb_publishable_test_key_never_logged"

        var serverStatus = 200
        var serverBody = """
            {
              "id": "$globalAdminId",
              "confirmed_at": "2026-11-01T10:00:00Z",
              "banned_until": null,
              "deleted_at": null,
              "is_anonymous": false,
              "factors": [
                {"id": "f1", "factor_type": "totp", "status": "verified"}
              ]
            }
        """.trimIndent()
        var responseDelayMs = 0L
        var redirectLocation: String? = null
        val redirectTargetHit = AtomicBoolean(false)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/user") { exchange ->
            if (responseDelayMs > 0) Thread.sleep(responseDelayMs)
            if (redirectLocation != null) {
                exchange.responseHeaders.add("Location", redirectLocation!!)
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return@createContext
            }
            val bytes = serverBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(serverStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/redirected") { exchange ->
            redirectTargetHit.set(true)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)

        try {
            val port = server.address.port
            val sessionVerifier = SupabaseAuthSessionVerifier(
                userEndpointUri = URI("http://127.0.0.1:$port/user"),
                timeout = Duration.ofMillis(700),
                apiKey = publicApiKey,
                clock = clock,
            )
            val authorizer = DefaultGlobalAdminAuthorizer(
                config = policyConfig(mfaMaxAgeSeconds = 300L),
                accessTokenVerifier = jwtVerifier(key, clock),
                sessionVerifier = sessionVerifier,
                clock = clock,
            )

            // 1. Happy path: valid global admin + live session + verified TOTP factor + fresh MFA -> Authorized
            val ok = assertIs<GlobalAdminAuthorizationResult.Authorized>(
                authorizer.authorize(validToken, correlationId = "req-ga-ok", operation = "test_global_op"),
            )
            assertEquals(globalAdminId, ok.principal.userId)
            assertEquals(issuer, ok.principal.issuer)
            assertEquals(sessionId, ok.principal.sessionId)
            assertEquals("aal2", ok.principal.aal)
            assertEquals("totp", ok.principal.mfaMethod)
            assertEquals(mfaVerifiedAt, ok.principal.mfaVerifiedAt)
            assertEquals(baseTime, ok.principal.authorizedAt)

            // 2. Session revoked (HTTP 403) -> SESSION_REVOKED_OR_INVALID
            serverStatus = 403
            serverBody = """{"error_code":"session_not_found","msg":"Session does not exist"}"""
            assertEquals(
                GlobalAdminDenialReason.SESSION_REVOKED_OR_INVALID,
                assertIs<GlobalAdminAuthorizationResult.Denied>(authorizer.authorize(validToken)).reason,
            )

            // 3. Banned user (HTTP 200 with future banned_until) -> ACCOUNT_NOT_ACTIVE_OR_UNCONFIRMED
            serverStatus = 200
            serverBody = """
                {
                  "id": "$globalAdminId",
                  "confirmed_at": "2026-11-01T10:00:00Z",
                  "banned_until": "2026-12-01T00:00:00Z",
                  "is_anonymous": false,
                  "factors": [{"id": "f1", "factor_type": "totp", "status": "verified"}]
                }
            """.trimIndent()
            assertEquals(
                GlobalAdminDenialReason.ACCOUNT_NOT_ACTIVE_OR_UNCONFIRMED,
                assertIs<GlobalAdminAuthorizationResult.Denied>(authorizer.authorize(validToken)).reason,
            )

            // 4. Auth 503 outage -> Unavailable (fail closed)
            serverStatus = 503
            serverBody = """{"msg":"service unavailable"}"""
            assertIs<GlobalAdminAuthorizationResult.Unavailable>(authorizer.authorize(validToken))

            // 5. Auth malformed JSON -> Unavailable (fail closed)
            serverStatus = 200
            serverBody = "not-json"
            assertIs<GlobalAdminAuthorizationResult.Unavailable>(authorizer.authorize(validToken))

            // 6. Auth 302 redirect -> Unavailable & MUST NOT follow redirect with Bearer token
            redirectLocation = "http://127.0.0.1:$port/redirected"
            assertIs<GlobalAdminAuthorizationResult.Unavailable>(authorizer.authorize(validToken))
            assertFalse(redirectTargetHit.get(), "Session verifier must never follow HTTP redirects")
            redirectLocation = null

            // 7. Auth timeout -> Unavailable (fail closed)
            serverStatus = 200
            serverBody = """{"id":"$globalAdminId"}"""
            responseDelayMs = 1_200L
            assertIs<GlobalAdminAuthorizationResult.Unavailable>(authorizer.authorize(validToken))
            responseDelayMs = 0L

            // 8. Connection refused -> Unavailable (fail closed)
            server.stop(0)
            assertIs<GlobalAdminAuthorizationResult.Unavailable>(authorizer.authorize(validToken))

            // 9. Verify ZERO tokens or API keys leaked in logs
            val allLogs = appender.list.joinToString("\n") { it.formattedMessage }
            assertFalse(allLogs.contains(validToken), "Raw JWT must never appear in logs")
            assertFalse(allLogs.contains(publicApiKey), "API key must never appear in logs")
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
            runCatching { server.stop(0) }
        }
    }
}
