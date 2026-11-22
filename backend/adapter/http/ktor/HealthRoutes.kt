package com.nexora.backend.adapter.http.ktor

import com.nexora.backend.application.health.HealthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val READINESS_TOKEN_HEADER = "X-Nexora-Readiness-Token"

fun Route.registerHealthRoutes(
    healthService: HealthService,
    readinessToken: String?,
) {
    get("/health/live") {
        call.respond(HttpStatusCode.OK, healthService.liveness())
    }

    get("/health/ready") {
        if (readinessToken != null) {
            val provided = call.request.headers[READINESS_TOKEN_HEADER]
            if (provided == null || !constantTimeEquals(provided, readinessToken)) {
                throw UnauthorizedApiException(
                    code = "invalid_readiness_token",
                    message = "Unauthorized readiness check",
                )
            }
        }

        val evaluation = healthService.readiness()
        val status = if (evaluation.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(status, evaluation.report)
    }
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(
        a.toByteArray(StandardCharsets.UTF_8),
        b.toByteArray(StandardCharsets.UTF_8),
    )
