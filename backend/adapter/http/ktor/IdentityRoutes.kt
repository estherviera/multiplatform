package com.nexora.backend.adapter.http.ktor

import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Instant
import java.util.UUID

data class IdentityResponse(
    val userId: UUID,
    val issuer: String,
    val audience: String,
    val sessionId: UUID? = null,
    val aal: String? = null,
    val expiresAt: Instant,
)

fun Route.registerIdentityRoutes() {
    authenticate(NEXORA_JWT_AUTH) {
        get("/api/v1/auth/me") {
            val principal = call.principal<AuthenticatedPrincipal>(NEXORA_JWT_AUTH)
                ?: throw UnauthorizedApiException(
                    code = "unauthorized",
                    message = "Authentication required",
                )

            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                HttpStatusCode.OK,
                IdentityResponse(
                    userId = principal.userId,
                    issuer = principal.issuer,
                    audience = principal.audience,
                    sessionId = principal.sessionId,
                    aal = principal.aal,
                    expiresAt = principal.expiresAt,
                ),
            )
        }
    }
}
