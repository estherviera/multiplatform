package com.nexora.backend.adapter.http.ktor

import com.nexora.backend.application.auth.AccessTokenVerifier
import com.nexora.backend.application.auth.TokenRejectionReason
import com.nexora.backend.application.auth.TokenVerificationResult
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

const val NEXORA_JWT_AUTH = "nexora-jwt"

private val authLogger = LoggerFactory.getLogger("com.nexora.backend.adapter.http.ktor.JwtAuthentication")

private class NexoraJwtAuthenticationProvider(
    config: Config,
) : AuthenticationProvider(config) {
    private val verifier: AccessTokenVerifier = config.verifier

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val authHeaders = call.request.headers.getAll(HttpHeaders.Authorization).orEmpty()
        val rawHeader = if (authHeaders.size == 1) authHeaders.first() else null
        val token = if (authHeaders.size > 1) null else extractBearerToken(rawHeader)
        if (token == null) {
            val code = if (authHeaders.isEmpty() || (authHeaders.size == 1 && rawHeader.isNullOrBlank())) {
                "missing_token"
            } else {
                "invalid_authorization_header"
            }
            context.challenge(NEXORA_JWT_AUTH, AuthenticationFailedCause.NoCredentials) { challenge, challengeCall ->
                challengeCall.response.header(HttpHeaders.WWWAuthenticate, """Bearer realm="nexora", error="invalid_token"""")
                challengeCall.respond(
                    HttpStatusCode.Unauthorized,
                    ApiErrorResponse(
                        error = ErrorDetail(
                            code = code,
                            message = "Authentication required",
                            requestId = challengeCall.callId,
                        ),
                    ),
                )
                challenge.complete()
            }
            return
        }

        when (val result = withContext(Dispatchers.IO) { verifier.verify(token) }) {
            is TokenVerificationResult.Valid -> {
                context.principal(NEXORA_JWT_AUTH, result.principal)
            }
            is TokenVerificationResult.Invalid -> {
                authLogger.warn(
                    "JWT rejected [requestId={}, method={}, path={}, reason={}]",
                    call.callId,
                    call.request.httpMethod.value,
                    call.request.path(),
                    result.reason.name,
                )
                val message = if (result.reason == TokenRejectionReason.TOKEN_EXPIRED) {
                    "Access token expired"
                } else {
                    "Invalid access token"
                }
                context.challenge(NEXORA_JWT_AUTH, AuthenticationFailedCause.InvalidCredentials) { challenge, challengeCall ->
                    challengeCall.response.header(
                        HttpHeaders.WWWAuthenticate,
                        """Bearer realm="nexora", error="${result.reason.wireCode}"""",
                    )
                    challengeCall.respond(
                        HttpStatusCode.Unauthorized,
                        ApiErrorResponse(
                            error = ErrorDetail(
                                code = result.reason.wireCode,
                                message = message,
                                requestId = challengeCall.callId,
                            ),
                        ),
                    )
                    challenge.complete()
                }
            }
            is TokenVerificationResult.Unavailable -> {
                authLogger.error(
                    "JWT verification unavailable [requestId={}, method={}, path={}, reason={}]",
                    call.callId,
                    call.request.httpMethod.value,
                    call.request.path(),
                    result.reason,
                )
                context.challenge(NEXORA_JWT_AUTH, AuthenticationFailedCause.Error(result.reason)) { challenge, challengeCall ->
                    challengeCall.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiErrorResponse(
                            error = ErrorDetail(
                                code = "auth_verifier_unavailable",
                                message = "Authentication verification temporarily unavailable",
                                requestId = challengeCall.callId,
                            ),
                        ),
                    )
                    challenge.complete()
                }
            }
        }
    }

    class Config(
        name: String,
        val verifier: AccessTokenVerifier,
    ) : AuthenticationProvider.Config(name)
}

fun Application.configureJwtAuthentication(verifier: AccessTokenVerifier) {
    install(Authentication) {
        register(NexoraJwtAuthenticationProvider(NexoraJwtAuthenticationProvider.Config(NEXORA_JWT_AUTH, verifier)))
    }
}

private fun extractBearerToken(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val parts = header.trim().split(' ')
    if (parts.size != 2) return null
    if (!parts[0].equals("Bearer", ignoreCase = true)) return null
    return parts[1].takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
}
