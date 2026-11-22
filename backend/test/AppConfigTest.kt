package com.nexora.backend

import com.nexora.backend.bootstrap.AppConfig
import com.nexora.backend.bootstrap.AppEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigTest {
    @Test
    fun `loads safe local defaults when env is empty`() {
        val config = AppConfig.fromEnv(emptyMap())
        assertEquals(AppEnvironment.LOCAL, config.environment)
        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertEquals("nexora-saas-api", config.serviceName)
        assertEquals(emptyList(), config.corsAllowedOrigins)
        assertNull(config.readinessToken)
        assertEquals(1_000L, config.shutdownGracePeriodMs)
        assertEquals(5_000L, config.shutdownTimeoutMs)
    }

    @Test
    fun `parses valid custom environment variables and redacts token in toString`() {
        val config = AppConfig.fromEnv(
            mapOf(
                "NEXORA_ENV" to "production",
                "NEXORA_HOST" to "0.0.0.0",
                "NEXORA_PORT" to "9090",
                "NEXORA_SERVICE_NAME" to "nexora-api",
                "NEXORA_CORS_ALLOWED_ORIGINS" to "https://app.nexora.edu, http://localhost:5173",
                "NEXORA_READINESS_TOKEN" to "super-secret-readiness-token",
                "NEXORA_AUTH_ISSUER" to "https://example.supabase.co/auth/v1",
                "NEXORA_AUTH_AUDIENCE" to "authenticated",
                "NEXORA_AUTH_JWKS_URL" to "https://example.supabase.co/auth/v1/.well-known/jwks.json",
                "NEXORA_AUTH_ALLOWED_ALGS" to "ES256, RS256",
                "NEXORA_AUTH_CLOCK_SKEW_SECONDS" to "15",
                "NEXORA_SHUTDOWN_GRACE_MS" to "2000",
                "NEXORA_SHUTDOWN_TIMEOUT_MS" to "8000",
            ),
        )

        assertEquals(AppEnvironment.PRODUCTION, config.environment)
        assertEquals("0.0.0.0", config.host)
        assertEquals(9090, config.port)
        assertEquals("nexora-api", config.serviceName)
        assertEquals(listOf("https://app.nexora.edu", "http://localhost:5173"), config.corsAllowedOrigins)
        assertEquals("super-secret-readiness-token", config.readinessToken)
        assertEquals("https://example.supabase.co/auth/v1", config.auth?.issuer)
        assertEquals("authenticated", config.auth?.audience)
        assertEquals(setOf("ES256", "RS256"), config.auth?.allowedAlgorithms)
        assertEquals(15L, config.auth?.clockSkewSeconds)
        assertEquals(2_000L, config.shutdownGracePeriodMs)
        assertEquals(8_000L, config.shutdownTimeoutMs)
        assertFalse(config.toString().contains("super-secret-readiness-token"))
        assertTrue(config.toString().contains("readinessTokenConfigured=true"))
        assertTrue(config.toString().contains("authConfigured=true"))
    }

    @Test
    fun `rejects invalid environment name`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(mapOf("NEXORA_ENV" to "dev"))
        }
    }

    @Test
    fun `rejects invalid port values`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(mapOf("NEXORA_PORT" to "not-a-number"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(mapOf("NEXORA_PORT" to "-1"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(mapOf("NEXORA_PORT" to "70000"))
        }
    }

    @Test
    fun `rejects wildcard and malformed CORS origins`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(mapOf("NEXORA_CORS_ALLOWED_ORIGINS" to "*"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(mapOf("NEXORA_CORS_ALLOWED_ORIGINS" to "https://app.nexora.edu/path"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(mapOf("NEXORA_CORS_ALLOWED_ORIGINS" to "ftp://app.nexora.edu"))
        }
    }

    @Test
    fun `fails closed in staging and production without readiness token`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "staging",
                    "NEXORA_AUTH_ISSUER" to "https://example.supabase.co/auth/v1",
                    "NEXORA_AUTH_JWKS_URL" to "https://example.supabase.co/auth/v1/.well-known/jwks.json",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "production",
                    "NEXORA_READINESS_TOKEN" to "   ",
                    "NEXORA_AUTH_ISSUER" to "https://example.supabase.co/auth/v1",
                    "NEXORA_AUTH_JWKS_URL" to "https://example.supabase.co/auth/v1/.well-known/jwks.json",
                ),
            )
        }
    }

    @Test
    fun `validates auth configuration and transport rules`() {
        val localAuth = AppConfig.fromEnv(
            mapOf(
                "NEXORA_ENV" to "local",
                "NEXORA_AUTH_ISSUER" to "http://127.0.0.1:55421",
                "NEXORA_AUTH_JWKS_URL" to "http://127.0.0.1:55421/.well-known/jwks.json",
            ),
        )
        assertEquals("http://127.0.0.1:55421", localAuth.auth?.issuer)
        assertEquals("authenticated", localAuth.auth?.audience)
        assertEquals(setOf("ES256"), localAuth.auth?.allowedAlgorithms)

        // Fails closed in staging/production without auth config
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "production",
                    "NEXORA_READINESS_TOKEN" to "ops-token",
                ),
            )
        }

        // Rejects partial auth config in local
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "local",
                    "NEXORA_AUTH_ISSUER" to "http://127.0.0.1:55421",
                ),
            )
        }

        // Rejects non-loopback http in local
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "local",
                    "NEXORA_AUTH_ISSUER" to "http://example.com/auth/v1",
                    "NEXORA_AUTH_JWKS_URL" to "http://example.com/auth/v1/.well-known/jwks.json",
                ),
            )
        }

        // Rejects http even on loopback in production
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "production",
                    "NEXORA_READINESS_TOKEN" to "ops-token",
                    "NEXORA_AUTH_ISSUER" to "http://127.0.0.1:55421",
                    "NEXORA_AUTH_JWKS_URL" to "http://127.0.0.1:55421/.well-known/jwks.json",
                ),
            )
        }

        // Rejects symmetric or none algorithms
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "local",
                    "NEXORA_AUTH_ISSUER" to "http://127.0.0.1:55421",
                    "NEXORA_AUTH_JWKS_URL" to "http://127.0.0.1:55421/.well-known/jwks.json",
                    "NEXORA_AUTH_ALLOWED_ALGS" to "HS256",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                mapOf(
                    "NEXORA_ENV" to "local",
                    "NEXORA_AUTH_ISSUER" to "http://127.0.0.1:55421",
                    "NEXORA_AUTH_JWKS_URL" to "http://127.0.0.1:55421/.well-known/jwks.json",
                    "NEXORA_AUTH_ALLOWED_ALGS" to "none",
                ),
            )
        }
    }
}
