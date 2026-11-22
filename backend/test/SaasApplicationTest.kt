package com.nexora.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.http.ktor.BadRequestApiException
import com.nexora.backend.adapter.http.ktor.FieldError
import com.nexora.backend.adapter.http.ktor.READINESS_TOKEN_HEADER
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.application.health.HealthCheckResult
import com.nexora.backend.application.health.HealthService
import com.nexora.backend.application.health.HealthStatus
import com.nexora.backend.application.health.ReadinessCheck
import com.nexora.backend.bootstrap.AppConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaasApplicationTest {
    private val json = ObjectMapper()

    @Test
    fun `liveness endpoint returns 200 ok with X-Request-Id`() = testApplication {
        val config = AppConfig.fromEnv(mapOf("NEXORA_ENV" to "test"))
        application {
            nexoraSaasModule(config)
        }

        val response = client.get("/health/live")
        assertEquals(HttpStatusCode.OK, response.status)
        val requestId = response.headers[HttpHeaders.XRequestId]
        assertNotNull(requestId)
        assertTrue(requestId.isNotBlank())

        val body = json.readTree(response.bodyAsText())
        assertEquals("ok", body["status"].asText())
    }

    @Test
    fun `readiness endpoint reports 200 when ready and 503 when a check fails`() = testApplication {
        val config = AppConfig.fromEnv(mapOf("NEXORA_ENV" to "test"))
        var healthy = true
        val healthService = HealthService(
            serviceName = config.serviceName,
            environment = config.environment.wireName,
            readinessChecks = mapOf(
                "probe" to ReadinessCheck {
                    if (healthy) HealthCheckResult(HealthStatus.OK) else HealthCheckResult(HealthStatus.NOT_READY)
                },
            ),
        )
        application {
            nexoraSaasModule(config, healthService)
        }

        val okResponse = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, okResponse.status)
        val okBody = json.readTree(okResponse.bodyAsText())
        assertEquals("ready", okBody["status"].asText())
        assertEquals("nexora-saas-api", okBody["service"].asText())
        assertEquals("test", okBody["environment"].asText())
        assertEquals("ok", okBody["checks"]["probe"].asText())

        healthy = false
        val degradedResponse = client.get("/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, degradedResponse.status)
        val degradedBody = json.readTree(degradedResponse.bodyAsText())
        assertEquals("not_ready", degradedBody["status"].asText())
        assertEquals("not_ready", degradedBody["checks"]["probe"].asText())
    }

    @Test
    fun `readiness endpoint enforces token when configured`() = testApplication {
        val config = AppConfig.fromEnv(
            mapOf(
                "NEXORA_ENV" to "staging",
                "NEXORA_READINESS_TOKEN" to "ops-token-123",
            ),
        )
        application {
            nexoraSaasModule(config)
        }

        val missing = client.get("/health/ready")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("invalid_readiness_token", json.readTree(missing.bodyAsText())["error"]["code"].asText())

        val wrong = client.get("/health/ready") {
            header(READINESS_TOKEN_HEADER, "wrong-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val valid = client.get("/health/ready") {
            header(READINESS_TOKEN_HEADER, "ops-token-123")
        }
        assertEquals(HttpStatusCode.OK, valid.status)
        assertEquals("ready", json.readTree(valid.bodyAsText())["status"].asText())
    }

    @Test
    fun `saas app does not mount legacy demo routes and returns consistent error envelope`() = testApplication {
        val config = AppConfig.fromEnv(mapOf("NEXORA_ENV" to "test"))
        application {
            nexoraSaasModule(config)
        }

        val legacyCommunities = client.get("/api/communities")
        assertEquals(HttpStatusCode.NotFound, legacyCommunities.status)
        val communitiesError = json.readTree(legacyCommunities.bodyAsText())["error"]
        assertEquals("not_found", communitiesError["code"].asText())
        assertNotNull(communitiesError["requestId"]?.asText())

        val legacyHealth = client.get("/health")
        assertEquals(HttpStatusCode.NotFound, legacyHealth.status)

        val wrongMethod = client.post("/health/live")
        assertEquals(HttpStatusCode.MethodNotAllowed, wrongMethod.status)
        val methodError = json.readTree(wrongMethod.bodyAsText())["error"]
        assertEquals("method_not_allowed", methodError["code"].asText())
    }

    @Test
    fun `handles typed ApiException and hides internal details on unexpected exceptions`() = testApplication {
        val config = AppConfig.fromEnv(mapOf("NEXORA_ENV" to "test"))
        application {
            nexoraSaasModule(config) {
                get("/_test/validation") {
                    throw BadRequestApiException(
                        code = "validation_failed",
                        message = "Validation failed",
                        details = listOf(FieldError("name", "must not be blank")),
                    )
                }
                get("/_test/boom") {
                    throw IllegalStateException("jdbc:postgresql://secret-host/db sensitive detail")
                }
            }
        }

        val badRequest = client.get("/_test/validation") {
            header(HttpHeaders.XRequestId, "req-test-42")
        }
        assertEquals(HttpStatusCode.BadRequest, badRequest.status)
        assertEquals("req-test-42", badRequest.headers[HttpHeaders.XRequestId])
        val badBody = json.readTree(badRequest.bodyAsText())["error"]
        assertEquals("validation_failed", badBody["code"].asText())
        assertEquals("req-test-42", badBody["requestId"].asText())
        assertEquals("name", badBody["details"][0]["field"].asText())

        val boom = client.get("/_test/boom")
        assertEquals(HttpStatusCode.InternalServerError, boom.status)
        val boomText = boom.bodyAsText()
        assertFalse(boomText.contains("secret-host"))
        assertFalse(boomText.contains("IllegalStateException"))
        val boomError = json.readTree(boomText)["error"]
        assertEquals("internal_error", boomError["code"].asText())
        assertEquals("Internal server error", boomError["message"].asText())
    }

    @Test
    fun `unexpected exception logs diagnostic metadata without leaking sensitive message`() = testApplication {
        val sensitiveMessage = "jdbc:postgresql://user:superSecretPass@secret-host:5432/nexora?token=abc123"
        val config = AppConfig.fromEnv(mapOf("NEXORA_ENV" to "test"))
        application {
            nexoraSaasModule(config) {
                get("/_test/sensitive-boom") {
                    throw IllegalStateException(
                        sensitiveMessage,
                        RuntimeException("Bearer secret-jwt-token-value"),
                    )
                }
            }
        }

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)
        try {
            val response = client.get("/_test/sensitive-boom") {
                header(HttpHeaders.XRequestId, "req-log-check-1")
            }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("req-log-check-1", response.headers[HttpHeaders.XRequestId])

            val errorBody = json.readTree(response.bodyAsText())["error"]
            assertEquals("internal_error", errorBody["code"].asText())
            assertEquals("Internal server error", errorBody["message"].asText())
            assertEquals("req-log-check-1", errorBody["requestId"].asText())

            val events = appender.list.toList()
            val errorEvent = events.firstOrNull {
                it.loggerName == "com.nexora.backend.adapter.http.ktor.ErrorHandling" && it.level == Level.ERROR
            }
            assertNotNull(errorEvent, "Expected an ERROR log event from ErrorHandling")
            assertNull(errorEvent.throwableProxy, "Unexpected exception should not attach raw throwable to log event")
            assertTrue(errorEvent.formattedMessage.contains("req-log-check-1"))
            assertTrue(errorEvent.formattedMessage.contains("java.lang.IllegalStateException"))
            assertTrue(errorEvent.formattedMessage.contains("java.lang.RuntimeException"))
            assertTrue(errorEvent.formattedMessage.contains("/_test/sensitive-boom"))

            val allLoggedText = events.joinToString("\n") { event ->
                val throwableText = event.throwableProxy?.let { "${it.className}: ${it.message}" } ?: ""
                "${event.formattedMessage} $throwableText"
            }
            assertFalse(allLoggedText.contains("superSecretPass"), "Sensitive password must not appear in logs")
            assertFalse(allLoggedText.contains("secret-host"), "Sensitive JDBC host must not appear in logs")
            assertFalse(allLoggedText.contains("jdbc:postgresql"), "Sensitive JDBC URI must not appear in logs")
            assertFalse(allLoggedText.contains("secret-jwt-token-value"), "Sensitive chained cause message must not appear in logs")
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `cors allows only configured origins`() = testApplication {
        val config = AppConfig.fromEnv(
            mapOf(
                "NEXORA_ENV" to "test",
                "NEXORA_CORS_ALLOWED_ORIGINS" to "http://localhost:5173",
            ),
        )
        application {
            nexoraSaasModule(config)
        }

        val allowed = client.options("/health/live") {
            header(HttpHeaders.Origin, "http://localhost:5173")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertEquals("http://localhost:5173", allowed.headers[HttpHeaders.AccessControlAllowOrigin])

        val blocked = client.options("/health/live") {
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertNull(blocked.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}
