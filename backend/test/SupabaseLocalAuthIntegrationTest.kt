package com.nexora.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.bootstrap.AppConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import org.junit.Assume
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real end-to-end integration test against local Supabase Auth (GoTrue).
 *
 * Opt-in via `-Dnexora.test.localAuth=true` so offline unit runs remain deterministic.
 * Never logs passwords, access tokens, or refresh tokens.
 */
class SupabaseLocalAuthIntegrationTest {
    private val json = ObjectMapper()
    private val authBaseUrl = System.getProperty("nexora.test.localAuthUrl", "http://127.0.0.1:55421").trimEnd('/')

    @Test
    fun `verifies real ES256 tokens from local Supabase Auth and rejects refresh token and post-logout refresh`() =
        testApplication {
            val enabled = System.getProperty("nexora.test.localAuth", "false").equals("true", ignoreCase = true)
            Assume.assumeTrue("Set -Dnexora.test.localAuth=true to run local Supabase Auth integration test", enabled)

            val http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()

            val healthResp = http.send(
                HttpRequest.newBuilder(URI("$authBaseUrl/health")).GET().timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, healthResp.statusCode(), "Local Supabase Auth must be healthy at $authBaseUrl")

            // Verify real JWKS publishes an asymmetric ES256 key
            val jwksResp = http.send(
                HttpRequest.newBuilder(URI("$authBaseUrl/.well-known/jwks.json")).GET().timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, jwksResp.statusCode())
            val jwksNode = json.readTree(jwksResp.body())
            val keysNode = jwksNode["keys"]
            assertTrue(keysNode != null && keysNode.size() > 0, "Local JWKS must contain at least one public key")
            assertEquals("ES256", keysNode[0]["alg"].asText())

            // Create a synthetic disposable user directly in local GoTrue (auto-confirmed in local config)
            val syntheticEmail = "nexora-it-${UUID.randomUUID()}@example.test"
            val randomBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val syntheticPassword = "Nx!" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)

            val signupBody = json.writeValueAsString(
                mapOf(
                    "email" to syntheticEmail,
                    "password" to syntheticPassword,
                ),
            )
            val signupResp = http.send(
                HttpRequest.newBuilder(URI("$authBaseUrl/signup"))
                    .POST(HttpRequest.BodyPublishers.ofString(signupBody))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, signupResp.statusCode(), "Local GoTrue signup should succeed")
            val signupJson = json.readTree(signupResp.body())
            val accessToken = signupJson["access_token"]?.asText()
            val refreshToken = signupJson["refresh_token"]?.asText()
            val userId = signupJson["user"]?.get("id")?.asText()
            assertNotNull(accessToken, "Expected real access_token from local GoTrue")
            assertNotNull(refreshToken, "Expected real refresh_token from local GoTrue")
            assertNotNull(userId, "Expected real user.id UUID from local GoTrue")

            val config = AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "local",
                    "NEXORA_AUTH_ISSUER" to authBaseUrl,
                    "NEXORA_AUTH_AUDIENCE" to "authenticated",
                    "NEXORA_AUTH_JWKS_URL" to "$authBaseUrl/.well-known/jwks.json",
                    "NEXORA_AUTH_ALLOWED_ALGS" to "ES256",
                ),
            )

            application {
                nexoraSaasModule(config)
            }

            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            rootLogger.addAppender(appender)

            try {
                // 1. Real access token is verified cryptographically via real JWKS
                val meResp = client.get("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
                assertEquals(HttpStatusCode.OK, meResp.status)
                val meJson = json.readTree(meResp.bodyAsText())
                assertEquals(userId, meJson["userId"].asText())
                assertEquals(authBaseUrl, meJson["issuer"].asText())
                assertEquals("authenticated", meJson["audience"].asText())
                assertNotNull(meJson["sessionId"]?.asText())

                // 2. Real refresh token must be rejected as Bearer on API
                val refreshAsBearer = client.get("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer $refreshToken")
                }
                assertEquals(HttpStatusCode.Unauthorized, refreshAsBearer.status)

                // 3. Real refresh flow yields a fresh valid access token for the same userId (T02)
                val refreshReqBody = json.writeValueAsString(mapOf("refresh_token" to refreshToken))
                val refreshResp = http.send(
                    HttpRequest.newBuilder(URI("$authBaseUrl/token?grant_type=refresh_token"))
                        .POST(HttpRequest.BodyPublishers.ofString(refreshReqBody))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, refreshResp.statusCode(), "Real token refresh should succeed")
                val refreshedJson = json.readTree(refreshResp.body())
                val refreshedAccessToken = refreshedJson["access_token"].asText()
                val refreshedRefreshToken = refreshedJson["refresh_token"].asText()

                val meAfterRefresh = client.get("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer $refreshedAccessToken")
                }
                assertEquals(HttpStatusCode.OK, meAfterRefresh.status)
                assertEquals(userId, json.readTree(meAfterRefresh.bodyAsText())["userId"].asText())

                // 4. Real logout revokes refresh token in GoTrue (T02)
                val logoutResp = http.send(
                    HttpRequest.newBuilder(URI("$authBaseUrl/logout"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .header("Authorization", "Bearer $refreshedAccessToken")
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(204, logoutResp.statusCode(), "Local GoTrue logout should return 204")

                val postLogoutRefresh = http.send(
                    HttpRequest.newBuilder(URI("$authBaseUrl/token?grant_type=refresh_token"))
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf("refresh_token" to refreshedRefreshToken))))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertTrue(
                    postLogoutRefresh.statusCode() in 400..499,
                    "Revoked refresh token must fail after logout, got ${postLogoutRefresh.statusCode()}",
                )

                // 5. Confirm zero token/credential leakage in backend logs
                val allLogs = appender.list.joinToString("\n") { it.formattedMessage }
                assertFalse(allLogs.contains(accessToken), "Real access token must never appear in logs")
                assertFalse(allLogs.contains(refreshedAccessToken), "Refreshed access token must never appear in logs")
                assertFalse(allLogs.contains(refreshToken), "Real refresh token must never appear in logs")
                assertFalse(allLogs.contains(syntheticPassword), "Synthetic password must never appear in logs")
            } finally {
                rootLogger.detachAppender(appender)
                appender.stop()
            }
        }
}
