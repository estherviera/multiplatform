package com.nexora.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.auth.CachingJwksKeyProvider
import com.nexora.backend.adapter.auth.HttpJwksFetcher
import com.nexora.backend.adapter.auth.JwtAuthConfig
import com.nexora.backend.adapter.auth.NimbusJwtAccessTokenVerifier
import com.nexora.backend.adapter.auth.SupabaseAuthSessionVerifier
import com.nexora.backend.application.auth.AuthSessionState
import com.nexora.backend.application.auth.DefaultGlobalAdminAuthorizer
import com.nexora.backend.application.auth.GlobalAdminPolicyConfig
import com.nexora.backend.application.auth.TokenVerificationResult
import com.nexora.backend.domain.auth.GlobalAdminAuthorizationResult
import com.nexora.backend.domain.auth.GlobalAdminDenialReason
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.slf4j.LoggerFactory

/**
 * Opt-in real E2E integration test against local Supabase Auth (`http://127.0.0.1:55421`, GoTrue v2.197.0)
 * with TOTP MFA enabled exclusively on loopback (`GOTRUE_MFA_TOTP_ENROLL_ENABLED=true`,
 * `GOTRUE_MFA_TOTP_VERIFY_ENABLED=true`).
 *
 * Verifies against real Supabase Auth (zero `service_role`, zero mocks):
 * 1. Real `aal1` ES256 token -> denied (`MFA_REQUIRED`).
 * 2. Real TOTP enrollment, challenge, RFC 6238 code verification -> real `aal2` ES256 JWT with `amr=[password, totp]`
 *    and `GET /user` returning verified TOTP factor -> positive `Authorized(GlobalAdminPrincipal)`.
 * 3. Real `refresh_token` grant without repeating MFA -> `iat` advances while `amr[totp].timestamp` stays fixed
 *    at the original MFA challenge instant; advancing clock past `mfaMaxAge` denies (`MFA_STEP_UP_EXPIRED`).
 * 4. Real `POST /logout` -> unexpired `aal2` JWT is immediately denied (`SESSION_REVOKED_OR_INVALID`).
 * 5. Zero tokens, refresh tokens, TOTP secrets, or passwords leaked in logs.
 *
 * Run with: `.\gradlew.bat -p backend test --tests com.nexora.backend.GlobalAdminSupabaseLocalIntegrationTest "-Dnexora.test.localAuth=true"`
 */
class GlobalAdminSupabaseLocalIntegrationTest {
    private val authBaseUrl = System.getProperty("nexora.test.localAuthUrl", "http://127.0.0.1:55421").trimEnd('/')
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    private class OffsetClock(private val offset: Duration) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = Instant.now().plus(offset)
    }

    @Test
    fun `verifies real TOTP enrollment, aal2 positive authorization, refresh amr invariance, MFA expiry, and logout revocation`(): Unit = runBlocking {
        val enabled = System.getProperty("nexora.test.localAuth", "false").equals("true", ignoreCase = true)
        Assume.assumeTrue("Set -Dnexora.test.localAuth=true to run local Supabase Auth tests", enabled)
        require(authBaseUrl.startsWith("http://127.0.0.1:") || authBaseUrl.startsWith("http://localhost:")) {
            "Local auth integration tests must target loopback only, got '$authBaseUrl'"
        }

        val email = "ga-mfa-${UUID.randomUUID().toString().take(8)}@nexora.local"
        val password = "GaMfa-${UUID.randomUUID().toString().replace("-", "")}!A1"

        // 1. Signup synthetic local test account
        val signupResp = postJson("$authBaseUrl/signup", """{"email":"$email","password":"$password"}""")
        assertEquals(200, signupResp.statusCode(), "Local Supabase Auth signup must succeed")
        val signupJson = mapper.readTree(signupResp.body())
        val aal1AccessToken = signupJson["access_token"].asText()
        val userId = UUID.fromString(signupJson["user"]["id"].asText())

        val authConfig = JwtAuthConfig(
            issuer = authBaseUrl,
            audience = "authenticated",
            jwksUri = URI("$authBaseUrl/.well-known/jwks.json"),
        )
        val keyProvider = CachingJwksKeyProvider(HttpJwksFetcher(authConfig.jwksUri))
        val jwtVerifier = NimbusJwtAccessTokenVerifier(config = authConfig, keyProvider = keyProvider)
        val sessionVerifier = SupabaseAuthSessionVerifier(
            userEndpointUri = URI("$authBaseUrl/user"),
            timeout = Duration.ofSeconds(3),
        )
        val policyConfig = GlobalAdminPolicyConfig(
            globalAdminUserId = userId,
            expectedIssuer = authBaseUrl,
            mfaMaxAge = Duration.ofSeconds(300),
            clockSkew = Duration.ofSeconds(30),
            requireVerifiedMfaFactorInSessionCheck = true,
        )
        val authorizer = DefaultGlobalAdminAuthorizer(
            config = policyConfig,
            accessTokenVerifier = jwtVerifier,
            sessionVerifier = sessionVerifier,
        )

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)

        var totpSecret = ""
        var aal2AccessToken = ""
        var refreshedAccessToken = ""
        var refreshToken = ""

        try {
            // 2. Real aal1 token MUST be denied (MFA_REQUIRED)
            val aal1Verified = assertIs<TokenVerificationResult.Valid>(jwtVerifier.verify(aal1AccessToken))
            assertEquals("aal1", aal1Verified.principal.aal)
            assertTrue(aal1Verified.principal.amr.any { it.method == "password" })
            assertEquals(
                GlobalAdminDenialReason.MFA_REQUIRED,
                assertIs<GlobalAdminAuthorizationResult.Denied>(
                    authorizer.authorize(aal1AccessToken, correlationId = "req-real-aal1", operation = "mfa_probe"),
                ).reason,
            )

            // 3. Real TOTP enrollment -> challenge -> RFC 6238 code verification
            val enrollResp = postJson(
                "$authBaseUrl/factors",
                """{"factor_type":"totp","friendly_name":"nexora-ga-test"}""",
                bearerToken = aal1AccessToken,
            )
            assertEquals(200, enrollResp.statusCode(), "Local Supabase Auth TOTP enrollment must succeed")
            val enrollJson = mapper.readTree(enrollResp.body())
            val factorId = enrollJson["id"].asText()
            totpSecret = enrollJson["totp"]["secret"].asText()

            val challengeResp = postJson(
                "$authBaseUrl/factors/$factorId/challenge",
                "{}",
                bearerToken = aal1AccessToken,
            )
            assertEquals(200, challengeResp.statusCode(), "Local Supabase Auth TOTP challenge must succeed")
            val challengeId = mapper.readTree(challengeResp.body())["id"].asText()

            val totpCode = computeTotpCode(totpSecret, Instant.now())
            val verifyResp = postJson(
                "$authBaseUrl/factors/$factorId/verify",
                """{"challenge_id":"$challengeId","code":"$totpCode"}""",
                bearerToken = aal1AccessToken,
            )
            assertEquals(200, verifyResp.statusCode(), "Local Supabase Auth TOTP verify must succeed")
            val verifyJson = mapper.readTree(verifyResp.body())
            aal2AccessToken = verifyJson["access_token"].asText()
            refreshToken = verifyJson["refresh_token"].asText()

            // 4. Positive real authorization with real aal2 JWT + real GET /user verified TOTP factor
            val aal2Jwt = assertIs<TokenVerificationResult.Valid>(jwtVerifier.verify(aal2AccessToken)).principal
            assertEquals("aal2", aal2Jwt.aal)
            val totpAmr = aal2Jwt.amr.firstOrNull { it.method == "totp" }
            assertNotNull(totpAmr, "Real GoTrue aal2 JWT must contain a 'totp' AMR entry with challenge timestamp")

            val liveActive = assertIs<AuthSessionState.Active>(sessionVerifier.verifyActiveSession(aal2AccessToken))
            assertTrue("totp" in liveActive.verifiedMfaFactorTypes, "Real GET /user must report verified totp factor")

            val authorized = assertIs<GlobalAdminAuthorizationResult.Authorized>(
                authorizer.authorize(aal2AccessToken, correlationId = "req-real-aal2-ok", operation = "mfa_probe"),
            )
            assertEquals(userId, authorized.principal.userId)
            assertEquals(aal2Jwt.sessionId, authorized.principal.sessionId)
            assertEquals("aal2", authorized.principal.aal)
            assertEquals("totp", authorized.principal.mfaMethod)
            assertEquals(totpAmr.authenticatedAt, authorized.principal.mfaVerifiedAt)

            // 5. Real token refresh after 2 seconds WITHOUT repeating MFA:
            //    `iat` advances, while `amr[totp].timestamp` MUST stay identical to the original challenge instant.
            Thread.sleep(2_100L)
            val refreshResp = postJson(
                "$authBaseUrl/token?grant_type=refresh_token",
                """{"refresh_token":"$refreshToken"}""",
            )
            assertEquals(200, refreshResp.statusCode(), "Real refresh_token grant must succeed")
            val refreshJson = mapper.readTree(refreshResp.body())
            refreshedAccessToken = refreshJson["access_token"].asText()

            val refreshedJwt = assertIs<TokenVerificationResult.Valid>(jwtVerifier.verify(refreshedAccessToken)).principal
            assertEquals("aal2", refreshedJwt.aal)
            assertEquals(aal2Jwt.sessionId, refreshedJwt.sessionId)
            assertTrue(
                refreshedJwt.issuedAt != null && aal2Jwt.issuedAt != null && refreshedJwt.issuedAt.isAfter(aal2Jwt.issuedAt),
                "Refreshed JWT must have a newer iat than the pre-refresh JWT",
            )
            val refreshedTotpAmr = refreshedJwt.amr.firstOrNull { it.method == "totp" }
            assertNotNull(refreshedTotpAmr)
            assertEquals(
                totpAmr.authenticatedAt,
                refreshedTotpAmr.authenticatedAt,
                "Real GoTrue refresh must preserve original TOTP AMR timestamp rather than resetting it to iat",
            )

            // 6. Expired MFA freshness window on the freshly refreshed, still-unexpired aal2 JWT -> MFA_STEP_UP_EXPIRED
            //    Shift clock forward by +305s (> 300s mfaMaxAge, well inside 3600s JWT exp).
            val futureClock = OffsetClock(Duration.ofSeconds(305))
            val expiredMfaAuthorizer = DefaultGlobalAdminAuthorizer(
                config = policyConfig,
                accessTokenVerifier = NimbusJwtAccessTokenVerifier(config = authConfig, keyProvider = keyProvider, clock = futureClock),
                sessionVerifier = sessionVerifier,
                clock = futureClock,
            )
            assertEquals(
                GlobalAdminDenialReason.MFA_STEP_UP_EXPIRED,
                assertIs<GlobalAdminAuthorizationResult.Denied>(
                    expiredMfaAuthorizer.authorize(refreshedAccessToken, correlationId = "req-real-mfa-stale", operation = "mfa_probe"),
                ).reason,
            )

            // 7. Real POST /logout revokes the session immediately: unexpired aal2 JWT -> SESSION_REVOKED_OR_INVALID
            val logoutResp = http.send(
                HttpRequest.newBuilder(URI("$authBaseUrl/logout"))
                    .header("Authorization", "Bearer $refreshedAccessToken")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(204, logoutResp.statusCode(), "Local Supabase Auth logout must succeed")

            assertIs<TokenVerificationResult.Valid>(jwtVerifier.verify(refreshedAccessToken))
            assertEquals(
                GlobalAdminDenialReason.SESSION_REVOKED_OR_INVALID,
                assertIs<GlobalAdminAuthorizationResult.Denied>(
                    authorizer.authorize(refreshedAccessToken, correlationId = "req-real-revoked", operation = "mfa_probe"),
                ).reason,
            )

            // 8. Verify zero real tokens, TOTP secrets, or passwords leaked in logs
            val allLogs = appender.list.joinToString("\n") { it.formattedMessage }
            for (secret in listOf(aal1AccessToken, aal2AccessToken, refreshedAccessToken, refreshToken, totpSecret, password)) {
                assertFalse(allLogs.contains(secret), "Sensitive credential/token must never appear in logs")
            }
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun postJson(url: String, body: String, bearerToken: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(5))
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /**
     * Standard RFC 6238 / RFC 4226 TOTP (HMAC-SHA1, 30s period, 6 digits) for test factor verification.
     */
    private fun computeTotpCode(base32Secret: String, instant: Instant): String {
        val keyBytes = decodeBase32(base32Secret)
        val counter = instant.epochSecond / 30L
        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun decodeBase32(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.uppercase().filter { it in alphabet }
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>((clean.length * 5) / 8)
        for (ch in clean) {
            buffer = (buffer shl 5) or alphabet.indexOf(ch)
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
