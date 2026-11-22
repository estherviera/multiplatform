package com.nexora.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.auth.CachingJwksKeyProvider
import com.nexora.backend.adapter.auth.JwksFetcher
import com.nexora.backend.adapter.auth.JwksUnavailableException
import com.nexora.backend.adapter.auth.JwtAuthConfig
import com.nexora.backend.adapter.auth.NimbusJwtAccessTokenVerifier
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.bootstrap.AppConfig
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ktor HTTP tests for JWT authentication and /api/v1/auth/me using ephemeral test keys.
 */
class AuthRoutesTest {
    private val json = ObjectMapper()
    private val issuer = "http://127.0.0.1:55421"
    private val audience = "authenticated"
    private val fixedNow = Instant.parse("2026-11-22T18:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun testKey(kid: String = "test-kid-1") =
        ECKeyGenerator(Curve.P_256)
            .keyID(kid)
            .keyUse(KeyUse.SIGNATURE)
            .keyOperations(setOf(KeyOperation.VERIFY))
            .algorithm(JWSAlgorithm.ES256)
            .generate()

    private fun testConfig(): AppConfig = AppConfig.fromEnv(
        mapOf(
            "NEXORA_ENV" to "test",
            "NEXORA_AUTH_ISSUER" to issuer,
            "NEXORA_AUTH_AUDIENCE" to audience,
            "NEXORA_AUTH_JWKS_URL" to "$issuer/.well-known/jwks.json",
        ),
    )

    private fun signToken(
        key: com.nimbusds.jose.jwk.ECKey,
        userId: UUID = UUID.randomUUID(),
        sessionId: UUID = UUID.randomUUID(),
        exp: Instant = fixedNow.plusSeconds(3600),
        role: String = "authenticated",
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(userId.toString())
            .issueTime(Date.from(fixedNow))
            .expirationTime(Date.from(exp))
            .claim("role", role)
            .claim("session_id", sessionId.toString())
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
    fun `protected endpoint returns verified principal and ignores client spoofing headers`() = testApplication {
        val key = testKey()
        val authConfig = JwtAuthConfig(issuer = issuer, audience = audience, jwksUri = URI("$issuer/.well-known/jwks.json"))
        val provider = CachingJwksKeyProvider(JwksFetcher { JWKSet(key.toPublicJWK()).toString() }, clock = fixedClock)
        val verifier = NimbusJwtAccessTokenVerifier(authConfig, provider, fixedClock)

        val realUserId = UUID.randomUUID()
        val spoofedUserId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val token = signToken(key, userId = realUserId, sessionId = sessionId)

        application {
            nexoraSaasModule(testConfig(), accessTokenVerifier = verifier)
        }

        val response = client.get("/api/v1/auth/me?userId=$spoofedUserId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-User-Id", spoofedUserId.toString())
            header("X-Company-Role", "company_admin")
            header(HttpHeaders.XRequestId, "req-auth-ok-1")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("req-auth-ok-1", response.headers[HttpHeaders.XRequestId])

        val body = json.readTree(response.bodyAsText())
        assertEquals(realUserId.toString(), body["userId"].asText())
        assertEquals(issuer, body["issuer"].asText())
        assertEquals(audience, body["audience"].asText())
        assertEquals(sessionId.toString(), body["sessionId"].asText())
        assertEquals("aal1", body["aal"].asText())
    }

    @Test
    fun `rejects missing, malformed, expired, and service_role tokens without logging raw token`() = testApplication {
        val key = testKey()
        val authConfig = JwtAuthConfig(issuer = issuer, audience = audience, jwksUri = URI("$issuer/.well-known/jwks.json"))
        val provider = CachingJwksKeyProvider(JwksFetcher { JWKSet(key.toPublicJWK()).toString() }, clock = fixedClock)
        val verifier = NimbusJwtAccessTokenVerifier(authConfig, provider, fixedClock)

        application {
            nexoraSaasModule(testConfig(), accessTokenVerifier = verifier)
        }

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)

        try {
            // Missing token
            val missing = client.get("/api/v1/auth/me")
            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertNotNull(missing.headers[HttpHeaders.WWWAuthenticate])
            assertEquals("missing_token", json.readTree(missing.bodyAsText())["error"]["code"].asText())

            // Non-Bearer header
            val basic = client.get("/api/v1/auth/me") {
                header(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz")
            }
            assertEquals(HttpStatusCode.Unauthorized, basic.status)
            assertEquals("invalid_authorization_header", json.readTree(basic.bodyAsText())["error"]["code"].asText())

            // Expired token
            val expiredToken = signToken(key, exp = fixedNow.minusSeconds(120))
            val expired = client.get("/api/v1/auth/me") {
                header(HttpHeaders.Authorization, "Bearer $expiredToken")
            }
            assertEquals(HttpStatusCode.Unauthorized, expired.status)
            assertEquals("token_expired", json.readTree(expired.bodyAsText())["error"]["code"].asText())

            // service_role token
            val serviceRoleToken = signToken(key, role = "service_role")
            val serviceRoleResp = client.get("/api/v1/auth/me") {
                header(HttpHeaders.Authorization, "Bearer $serviceRoleToken")
            }
            assertEquals(HttpStatusCode.Unauthorized, serviceRoleResp.status)
            assertEquals("invalid_token", json.readTree(serviceRoleResp.bodyAsText())["error"]["code"].asText())

            // Public health routes remain accessible without Authorization
            val live = client.get("/health/live")
            assertEquals(HttpStatusCode.OK, live.status)

            // Raw tokens must NEVER appear in captured logs
            val allLogs = appender.list.joinToString("\n") { it.formattedMessage }
            assertFalse(allLogs.contains(expiredToken), "Expired raw JWT must not appear in logs")
            assertFalse(allLogs.contains(serviceRoleToken), "Service role raw JWT must not appear in logs")
            assertTrue(allLogs.contains("TOKEN_EXPIRED"))
            assertTrue(allLogs.contains("DISALLOWED_TOKEN_CLASS"))
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `fails closed with 503 when JWKS is unavailable or auth is unconfigured`() = testApplication {
        val key = testKey()
        val authConfig = JwtAuthConfig(issuer = issuer, audience = audience, jwksUri = URI("$issuer/.well-known/jwks.json"))
        val failingProvider = CachingJwksKeyProvider(
            JwksFetcher { throw JwksUnavailableException("jwks_connection_refused") },
            clock = fixedClock,
        )
        val verifier = NimbusJwtAccessTokenVerifier(authConfig, failingProvider, fixedClock)

        application {
            nexoraSaasModule(testConfig(), accessTokenVerifier = verifier)
        }

        val resp = client.get("/api/v1/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${signToken(key)}")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        assertEquals("auth_verifier_unavailable", json.readTree(resp.bodyAsText())["error"]["code"].asText())
    }

    @Test
    fun `unconfigured local auth keeps health live open and fails closed on protected routes`() = testApplication {
        val unconfigured = AppConfig.fromEnv(mapOf("NEXORA_ENV" to "test"))
        application {
            nexoraSaasModule(unconfigured)
        }

        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)

        val resp = client.get("/api/v1/auth/me") {
            header(HttpHeaders.Authorization, "Bearer dummy.jwt.token")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        assertEquals("auth_verifier_unavailable", json.readTree(resp.bodyAsText())["error"]["code"].asText())
    }
}
