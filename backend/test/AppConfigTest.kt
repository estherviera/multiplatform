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
        assertEquals(2_000L, config.shutdownGracePeriodMs)
        assertEquals(8_000L, config.shutdownTimeoutMs)
        assertFalse(config.toString().contains("super-secret-readiness-token"))
        assertTrue(config.toString().contains("readinessTokenConfigured=true"))
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
            AppConfig.fromEnv(mapOf("NEXORA_ENV" to "staging"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(mapOf("NEXORA_ENV" to "production", "NEXORA_READINESS_TOKEN" to "   "))
        }
    }
}
