package com.nexora.backend.adapter.http.ktor

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.nexora.backend.application.auth.GlobalAdminAuthorizer
import com.nexora.backend.application.multitenant.DomainValidationException
import com.nexora.backend.application.multitenant.ValidationViolation
import com.nexora.backend.application.platform.CreateCompanyCommand
import com.nexora.backend.application.platform.PlatformCompanyService
import com.nexora.backend.application.platform.PlatformRateLimiter
import com.nexora.backend.domain.auth.GlobalAdminAuthorizationResult
import com.nexora.backend.domain.auth.GlobalAdminDenialReason
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val MAX_PLATFORM_REQUEST_BYTES = 8_192

private val platformRequestMapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

data class PlatformCreateCompanyRequest(
    val slug: String? = null,
    val name: String? = null,
    val initialAdminUserId: String? = null,
)

data class PlatformCreateCompanyResponse(
    val company: CompanyResponse,
    val initialAdminMembership: MembershipResponse,
    val idempotencyReplayed: Boolean,
)

fun Route.registerPlatformCompanyRoutes(
    globalAdminAuthorizer: GlobalAdminAuthorizer,
    platformCompanyService: PlatformCompanyService?,
    rateLimiter: PlatformRateLimiter,
    clientIpResolver: ClientIpResolver = ClientIpResolver(),
) {
    post("/api/v1/platform/companies") {
        call.response.header(HttpHeaders.CacheControl, "no-store")

        // 1. Apply rate limiting BEFORE expensive cryptographic / Auth / DB work.
        //    Uses direct TCP peer unless peer is an explicitly configured trusted proxy.
        val callerBucket = clientIpResolver.resolve(
            peerAddress = call.request.local.remoteAddress,
            xForwardedForHeaders = call.request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty(),
            forwardedHeaders = call.request.headers.getAll(HttpHeaders.Forwarded).orEmpty(),
        )
        val rateDecision = rateLimiter.tryAcquire(callerBucket)
        if (!rateDecision.allowed) {
            if (rateDecision.shouldAuditViolation) {
                platformCompanyService?.recordRateLimitAudit(call.callId)
            }
            call.response.header(HttpHeaders.RetryAfter, rateDecision.retryAfterSeconds.toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = "rate_limit_exceeded",
                        message = "Too many platform requests; retry later",
                        requestId = call.callId,
                    ),
                ),
            )
            return@post
        }

        // Reject ambiguous duplicate Authorization headers (HTTP header smuggling defense).
        val authHeaders = call.request.headers.getAll(HttpHeaders.Authorization).orEmpty()
        val rawHeader = if (authHeaders.size == 1) authHeaders.first() else null
        val rawBearer = if (authHeaders.size > 1) null else extractPlatformBearerToken(rawHeader)

        // 2. Strict global administrator verification:
        //    NEXORA_GLOBAL_ADMIN_SUB + JWT crypto + live Supabase Auth /user + MFA aal2 + fresh amr.
        val globalAdmin = when (
            val authResult = globalAdminAuthorizer.authorize(
                rawAccessToken = rawBearer,
                correlationId = call.callId,
                operation = "platform.company.create",
            )
        ) {
            is GlobalAdminAuthorizationResult.Authorized -> authResult.principal
            is GlobalAdminAuthorizationResult.Denied -> {
                platformCompanyService?.recordDenialAudit(authResult.reason.name, call.callId)
                respondGlobalAdminDenial(call, authResult.reason, if (authHeaders.isEmpty()) null else "present")
                return@post
            }
            is GlobalAdminAuthorizationResult.Unavailable -> {
                platformCompanyService?.recordAuthUnavailableAudit(authResult.reason, call.callId)
                throw ServiceUnavailableApiException(
                    code = "auth_verifier_unavailable",
                    message = "Authentication verification temporarily unavailable",
                )
            }
        }

        // 3. Require dedicated platform SQL executor (`app_platform`).
        val service = platformCompanyService ?: throw ServiceUnavailableApiException(
            code = "platform_executor_unavailable",
            message = "Platform company executor is not configured",
        )

        // 4. Reject ambiguous duplicate Idempotency-Key headers and enforce bounded JSON body size.
        val idempotencyHeaders = call.request.headers.getAll("Idempotency-Key").orEmpty()
        if (idempotencyHeaders.size > 1) {
            throw DomainValidationException(
                listOf(ValidationViolation("Idempotency-Key", "must not be supplied more than once")),
            )
        }
        val idempotencyKey = idempotencyHeaders.firstOrNull()

        val declaredLength = call.request.contentLength()
        if (declaredLength != null && declaredLength > MAX_PLATFORM_REQUEST_BYTES) {
            throw PayloadTooLargeApiException(
                code = "payload_too_large",
                message = "Request body exceeds maximum allowed size",
            )
        }
        val rawBytes = readBoundedRequestBytes(call.receiveChannel(), MAX_PLATFORM_REQUEST_BYTES)
        val body = platformRequestMapper.readValue(
            String(rawBytes, StandardCharsets.UTF_8),
            PlatformCreateCompanyRequest::class.java,
        )
        val initialAdminId = parseCanonicalPlatformUuid(body.initialAdminUserId)
            ?: throw DomainValidationException(
                listOf(ValidationViolation("initialAdminUserId", "must be a valid canonical UUID")),
            )

        val result = service.createCompany(
            principal = globalAdmin,
            command = CreateCompanyCommand(
                slug = body.slug,
                name = body.name,
                initialAdminUserId = initialAdminId,
                idempotencyKey = idempotencyKey,
            ),
            correlationId = call.callId,
        )

        if (result.idempotencyReplayed) {
            call.response.header("X-Idempotency-Replayed", "true")
        }
        val status = if (result.idempotencyReplayed) HttpStatusCode.OK else HttpStatusCode.Created
        call.respond(
            status,
            PlatformCreateCompanyResponse(
                company = CompanyResponse(
                    id = result.company.id,
                    slug = result.company.slug,
                    name = result.company.name,
                    status = result.company.status.wireValue,
                    createdAt = result.company.createdAt,
                    updatedAt = result.company.updatedAt,
                ),
                initialAdminMembership = MembershipResponse(
                    companyId = result.initialAdminMembership.companyId,
                    userId = result.initialAdminMembership.userId,
                    role = result.initialAdminMembership.role.wireValue,
                    status = result.initialAdminMembership.status.wireValue,
                    createdAt = result.initialAdminMembership.createdAt,
                    updatedAt = result.initialAdminMembership.updatedAt,
                ),
                idempotencyReplayed = result.idempotencyReplayed,
            ),
        )
    }
}

private suspend fun respondGlobalAdminDenial(
    call: io.ktor.server.application.ApplicationCall,
    reason: GlobalAdminDenialReason,
    rawHeader: String?,
) {
    val (status, code, message) = when (reason) {
        GlobalAdminDenialReason.GLOBAL_ADMIN_DISABLED -> Triple(
            HttpStatusCode.Forbidden,
            reason.wireCode,
            "Global platform administration is disabled",
        )
        GlobalAdminDenialReason.INVALID_TOKEN -> Triple(
            HttpStatusCode.Unauthorized,
            if (rawHeader.isNullOrBlank()) "missing_token" else reason.wireCode,
            "Authentication required",
        )
        GlobalAdminDenialReason.TOKEN_EXPIRED -> Triple(
            HttpStatusCode.Unauthorized,
            reason.wireCode,
            "Access token expired",
        )
        GlobalAdminDenialReason.ISSUER_MISMATCH,
        GlobalAdminDenialReason.USER_IDENTITY_MISMATCH -> Triple(
            HttpStatusCode.Unauthorized,
            reason.wireCode,
            "Invalid access token",
        )
        GlobalAdminDenialReason.MISSING_SESSION_ID -> Triple(
            HttpStatusCode.Unauthorized,
            reason.wireCode,
            "Verified active session is required",
        )
        GlobalAdminDenialReason.SESSION_REVOKED_OR_INVALID -> Triple(
            HttpStatusCode.Unauthorized,
            reason.wireCode,
            "Session is no longer active",
        )
        GlobalAdminDenialReason.NOT_GLOBAL_ADMIN -> Triple(
            HttpStatusCode.Forbidden,
            reason.wireCode,
            "Global administrator privileges are required",
        )
        GlobalAdminDenialReason.MFA_REQUIRED,
        GlobalAdminDenialReason.MFA_FACTOR_NOT_VERIFIED -> Triple(
            HttpStatusCode.Forbidden,
            reason.wireCode,
            "Verified MFA (aal2) is required",
        )
        GlobalAdminDenialReason.MFA_EVIDENCE_MISSING,
        GlobalAdminDenialReason.MFA_STEP_UP_EXPIRED -> Triple(
            HttpStatusCode.Forbidden,
            reason.wireCode,
            "Recent MFA re-authentication is required",
        )
        GlobalAdminDenialReason.ACCOUNT_NOT_ACTIVE_OR_UNCONFIRMED -> Triple(
            HttpStatusCode.Forbidden,
            reason.wireCode,
            "Account is not active and confirmed",
        )
    }

    if (status == HttpStatusCode.Unauthorized) {
        call.response.header(HttpHeaders.WWWAuthenticate, """Bearer realm="nexora", error="$code"""")
    }
    call.respond(
        status,
        ApiErrorResponse(
            error = ErrorDetail(
                code = code,
                message = message,
                requestId = call.callId,
            ),
        ),
    )
}

private fun extractPlatformBearerToken(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val parts = header.trim().split(' ')
    if (parts.size != 2) return null
    if (!parts[0].equals("Bearer", ignoreCase = true)) return null
    return parts[1].takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
}

private fun parseCanonicalPlatformUuid(raw: String?): UUID? {
    val trimmed = raw?.trim()
    if (trimmed.isNullOrEmpty()) return null
    return try {
        UUID.fromString(trimmed).takeIf { it.toString().equals(trimmed, ignoreCase = true) }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private suspend fun readBoundedRequestBytes(
    channel: io.ktor.utils.io.ByteReadChannel,
    maxBytes: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(2048)
    var total = 0
    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buf, 0, buf.size)
        if (read == -1) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            throw PayloadTooLargeApiException(
                code = "payload_too_large",
                message = "Request body exceeds maximum allowed size",
            )
        }
        out.write(buf, 0, read)
    }
    return out.toByteArray()
}
