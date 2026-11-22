package com.nexora.backend.adapter.http.ktor

import com.nexora.backend.application.multitenant.CompanyQueryService
import com.nexora.backend.application.multitenant.DomainValidationException
import com.nexora.backend.application.multitenant.ProfileService
import com.nexora.backend.application.multitenant.ValidationViolation
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import com.nexora.backend.domain.multitenant.Company
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.PageRequest
import com.nexora.backend.domain.multitenant.Profile
import com.nexora.backend.domain.multitenant.UserCompanySummary
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import java.time.Instant
import java.util.UUID

data class UpsertProfileRequest(
    val displayName: String? = null,
    val avatarPath: String? = null,
)

data class ProfileResponse(
    val id: UUID,
    val displayName: String,
    val avatarPath: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CompanyResponse(
    val id: UUID,
    val slug: String,
    val name: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class UserCompanyResponse(
    val id: UUID,
    val slug: String,
    val name: String,
    val status: String,
    val role: String,
    val membershipStatus: String,
    val joinedAt: Instant,
)

data class MembershipResponse(
    val companyId: UUID,
    val userId: UUID,
    val role: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PagedResponse<T>(
    val items: List<T>,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean,
)

fun Route.registerMultitenantRoutes(
    profileService: ProfileService?,
    companyQueryService: CompanyQueryService?,
) {
    authenticate(NEXORA_JWT_AUTH) {
        get("/api/v1/me/profile") {
            val principal = call.requireVerifiedPrincipal()
            val service = profileService ?: throw persistenceUnavailable()
            val profile = service.getMyProfile(principal)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, profile.toResponse())
        }

        put("/api/v1/me/profile") {
            val principal = call.requireVerifiedPrincipal()
            val service = profileService ?: throw persistenceUnavailable()
            val request = call.receiveBoundedJson(UpsertProfileRequest::class.java)
            val profile = service.upsertMyProfile(
                principal = principal,
                rawDisplayName = request.displayName,
                rawAvatarPath = request.avatarPath,
            )
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, profile.toResponse())
        }

        get("/api/v1/me/companies") {
            val principal = call.requireVerifiedPrincipal()
            val service = companyQueryService ?: throw persistenceUnavailable()
            val page = call.parsePageRequest()
            val result = service.listMyCompanies(principal, page)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                HttpStatusCode.OK,
                PagedResponse(
                    items = result.items.map { it.toResponse() },
                    limit = result.limit,
                    offset = result.offset,
                    hasMore = result.hasMore,
                ),
            )
        }

        get("/api/v1/companies/{companyId}") {
            val principal = call.requireVerifiedPrincipal()
            val service = companyQueryService ?: throw persistenceUnavailable()
            val companyId = call.parseCanonicalUuidParam("companyId")
            val company = service.getCompany(principal, companyId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, company.toResponse())
        }

        get("/api/v1/companies/{companyId}/membership") {
            val principal = call.requireVerifiedPrincipal()
            val service = companyQueryService ?: throw persistenceUnavailable()
            val companyId = call.parseCanonicalUuidParam("companyId")
            val membership = service.getMyMembership(principal, companyId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, membership.toResponse())
        }

        get("/api/v1/companies/{companyId}/memberships") {
            val principal = call.requireVerifiedPrincipal()
            val service = companyQueryService ?: throw persistenceUnavailable()
            val companyId = call.parseCanonicalUuidParam("companyId")
            val page = call.parsePageRequest()
            val result = service.listVisibleMemberships(principal, companyId, page)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                HttpStatusCode.OK,
                PagedResponse(
                    items = result.items.map { it.toResponse() },
                    limit = result.limit,
                    offset = result.offset,
                    hasMore = result.hasMore,
                ),
            )
        }
    }
}

private fun ApplicationCall.requireVerifiedPrincipal(): AuthenticatedPrincipal =
    principal<AuthenticatedPrincipal>(NEXORA_JWT_AUTH)
        ?: throw UnauthorizedApiException(code = "unauthorized", message = "Authentication required")

private fun persistenceUnavailable(): ServiceUnavailableApiException =
    ServiceUnavailableApiException(
        code = "persistence_unavailable",
        message = "Database persistence is not configured",
    )

private fun ApplicationCall.parseCanonicalUuidParam(name: String): UUID {
    val raw = parameters[name]?.trim()
    val parsed = if (raw.isNullOrEmpty()) {
        null
    } else {
        try {
            UUID.fromString(raw).takeIf { it.toString().equals(raw, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    return parsed ?: throw BadRequestApiException(
        code = "invalid_company_id",
        message = "Invalid company identifier",
    )
}

private fun ApplicationCall.parsePageRequest(): PageRequest {
    val violations = mutableListOf<ValidationViolation>()

    val limitRaw = request.queryParameters["limit"]?.trim()
    val limit = if (limitRaw.isNullOrEmpty()) {
        PageRequest.DEFAULT_LIMIT
    } else {
        val parsed = limitRaw.toIntOrNull()
        if (parsed == null || parsed !in 1..PageRequest.MAX_LIMIT) {
            violations.add(ValidationViolation("limit", "must be an integer between 1 and ${PageRequest.MAX_LIMIT}"))
            PageRequest.DEFAULT_LIMIT
        } else {
            parsed
        }
    }

    val offsetRaw = request.queryParameters["offset"]?.trim()
    val offset = if (offsetRaw.isNullOrEmpty()) {
        PageRequest.DEFAULT_OFFSET
    } else {
        val parsed = offsetRaw.toIntOrNull()
        if (parsed == null || parsed !in 0..PageRequest.MAX_OFFSET) {
            violations.add(ValidationViolation("offset", "must be an integer between 0 and ${PageRequest.MAX_OFFSET}"))
            PageRequest.DEFAULT_OFFSET
        } else {
            parsed
        }
    }

    if (violations.isNotEmpty()) {
        throw DomainValidationException(violations)
    }
    return PageRequest(limit = limit, offset = offset)
}

private fun Profile.toResponse() = ProfileResponse(
    id = id,
    displayName = displayName,
    avatarPath = avatarPath,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Company.toResponse() = CompanyResponse(
    id = id,
    slug = slug,
    name = name,
    status = status.wireValue,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun UserCompanySummary.toResponse() = UserCompanyResponse(
    id = company.id,
    slug = company.slug,
    name = company.name,
    status = company.status.wireValue,
    role = role.wireValue,
    membershipStatus = membershipStatus.wireValue,
    joinedAt = joinedAt,
)

private fun CompanyMembership.toResponse() = MembershipResponse(
    companyId = companyId,
    userId = userId,
    role = role.wireValue,
    status = status.wireValue,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
