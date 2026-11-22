package com.nexora.backend.adapter.http.ktor

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.nexora.backend.application.health.HealthService
import com.nexora.backend.bootstrap.AppConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import java.net.URI
import java.util.UUID
import org.slf4j.event.Level

private val SAFE_REQUEST_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,64}$")

fun Application.nexoraSaasModule(
    config: AppConfig,
    healthService: HealthService = HealthService(config.serviceName, config.environment.wireName),
    additionalRoutes: Route.() -> Unit = {},
) {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        verify { it.matches(SAFE_REQUEST_ID_REGEX) }
        generate { UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
    }

    install(ContentNegotiation) {
        jackson {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        }
    }

    if (config.corsAllowedOrigins.isNotEmpty()) {
        install(CORS) {
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Options)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.XRequestId)
            exposeHeader(HttpHeaders.XRequestId)
            config.corsAllowedOrigins.forEach { origin ->
                val uri = URI(origin)
                val hostWithPort = if (uri.port != -1) "${uri.host}:${uri.port}" else uri.host
                allowHost(hostWithPort, schemes = listOf(uri.scheme.lowercase()))
            }
        }
    }

    configureErrorHandling()

    routing {
        registerHealthRoutes(healthService, config.readinessToken)
        additionalRoutes()
    }
}
