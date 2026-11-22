package com.nexora.backend.adapter.http.ktor

import com.nexora.backend.application.multitenant.DomainValidationException
import com.nexora.backend.application.multitenant.MembershipManagementService
import com.nexora.backend.application.multitenant.ValidationViolation
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import com.nexora.backend.domain.multitenant.CompanyInvitation
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.PageRequest
import com.nexora.backend.domain.multitenant.ReplaceAdminResult
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import java.time.Instant
import java.util.UUID

data class CreateInvitationRequest(
    val inviteeUserId: String? = null,
    val role: String? = null,
)

data class ChangeRoleRequest(
    val role: String? = null,
)

data class ReplaceAdminRequest(
    val outgoingAdminUserId: String? = null,
    val successorUserId: String? = null,
    val outgoingNewRole: String? = null,
)

data class InvitationResponse(
    val id: UUID,
    val companyId: UUID,
    val inviteeUserId: UUID,
    val role: String,
    val status: String,
    val expiresAt: Instant,
    val respondedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ReplaceAdminResponse(
    val outgoingMembership: MembershipResponse,
    val successorMembership: MembershipResponse,
)

fun Route.registerMembershipManagementRoutes(
    membershipManagementService: MembershipManagementService?,
) {
    authenticate(NEXORA_JWT_AUTH) {
        post("/api/v1/companies/{companyId}/invitations") {
            val principal = call.requirePrincipal2C2()
            val service = membershipManagementService ?: throw persistenceUnavailable2C2()
            val companyId = call.parseUuidPathParam("companyId", "invalid_company_id", "Invalid company identifier")
            val body = call.receiveBoundedJson(CreateInvitationRequest::class.java)
            val inviteeId = parseUuidBodyField(body.inviteeUserId, "inviteeUserId")

            val outcome = service.createInvitation(
                principal = principal,
                companyId = companyId,
                inviteeUserId = inviteeId,
                rawRole = body.role,
                correlationId = call.callId,
            )
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val status = if (outcome.created) HttpStatusCode.Created else HttpStatusCode.OK
            call.respond(status, outcome.invitation.toResponse())
        }

        get("/api/v1/companies/{companyId}/invitations") {
            val principal = call.requirePrincipal2C2()
            val service = membershipManagementService ?: throw persistenceUnavailable2C2()
            val companyId = call.parseUuidPathParam("companyId", "invalid_company_id", "Invalid company identifier")
            val page = call.parsePageRequest2C2()
            val result = service.listCompanyInvitations(principal, companyId, page)
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

        get("/api/v1/me/invitations") {
            val principal = call.requirePrincipal2C2()
            val service = membershipManagementService ?: throw persistenceUnavailable2C2()
            val page = call.parsePageRequest2C2()
            val result = service.listMyInvitations(principal, page)
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

        get("/api/v1/me/invitations/{invitationId}") {
            val principal = call.requirePrincipal2C2()
            val service = membershipManagementService ?: throw persistenceUnavailable2C2()
            val invitationId = call.parseUuidPathParam("invitationId", "invalid_invitation_id", "Invalid invitation identifier")
            val invitation = service.getMyInvitation(principal, invitationId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, invitation.toResponse())
        }

        post("/api/v1/me/invitations/{invitationId}/accept") {
            val principal = call.requirePrincipal2C2()
            val service = membershipManagementService ?: throw persistenceUnavailable2C2()
            val invitationId = call.parseUuidPathParam("invitationId", "invalid_invitation_id", "Invalid invitation identifier")
            val membership = service.acceptInvitation(principal, invitationId, call.callId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, membership.toMembershipResponse())
        }

        post("/api/v1/me/invitations/{invitationId}/decline") {
            val principal = call.requirePrincipal2C2()
            val service = membershipManagementService ?: throw persistenceUnavailable2C2()
            val invitationId = call.parseUuidPathParam("invitationId", "invalid_invitation_id", "Invalid invitation identifier")
            val invitation = service.declineInvitation(principal, invitationId, call.callId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, invitation.toResponse())
        }

        patch("/api/v1/companies/{companyId}/memberships/{userId}/role") {
            val principal = call.requirePrincipal2C2()
            val service = membershipManagementService ?: throw persistenceUnavailable2C2()
            val companyId = call.parseUuidPathParam("companyId", "invalid_company_id", "Invalid company identifier")
            val targetUserId = call.parseUuidPathParam("userId", "invalid_user_id", "Invalid user identifier")
            val body = call.receiveBoundedJson(ChangeRoleRequest::class.java)
            val updated = service.changeMemberRole(principal, companyId, targetUserId, body.role, call.callId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, updated.toMembershipResponse())
        }

        post("/api/v1/companies/{companyId}/memberships/{userId}/suspend") {
            val principal = call.requirePrincipal2C2()
            val service = membershipManagementService ?: throw persistenceUnavailable2C2()
            val companyId = call.parseUuidPathParam("companyId", "invalid_company_id", "Invalid company identifier")
            val targetUserId = call.parseUuidPathParam("userId", "invalid_user_id", "Invalid user identifier")
            val updated = service.suspendMember(principal, companyId, targetUserId, call.callId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, updated.toMembershipResponse())
        }

        post("/api/v1/companies/{companyId}/memberships/{userId}/reactivate") {
            val principal = call.requirePrincipal2C2()
            val service = membershipManagementService ?: throw persistenceUnavailable2C2()
            val companyId = call.parseUuidPathParam("companyId", "invalid_company_id", "Invalid company identifier")
            val targetUserId = call.parseUuidPathParam("userId", "invalid_user_id", "Invalid user identifier")
            val updated = service.reactivateMember(principal, companyId, targetUserId, call.callId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, updated.toMembershipResponse())
        }

        post("/api/v1/companies/{companyId}/admins/replace") {
            val principal = call.requirePrincipal2C2()
            val service = membershipManagementService ?: throw persistenceUnavailable2C2()
            val companyId = call.parseUuidPathParam("companyId", "invalid_company_id", "Invalid company identifier")
            val body = call.receiveBoundedJson(ReplaceAdminRequest::class.java)
            val outgoingId = parseUuidBodyField(body.outgoingAdminUserId, "outgoingAdminUserId")
            val successorId = parseUuidBodyField(body.successorUserId, "successorUserId")
            val result = service.replaceAdmin(
                principal = principal,
                companyId = companyId,
                outgoingAdminUserId = outgoingId,
                successorUserId = successorId,
                rawOutgoingNewRole = body.outgoingNewRole,
                correlationId = call.callId,
            )
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, result.toResponse())
        }
    }
}

private fun ApplicationCall.requirePrincipal2C2(): AuthenticatedPrincipal =
    principal<AuthenticatedPrincipal>(NEXORA_JWT_AUTH)
        ?: throw UnauthorizedApiException(code = "unauthorized", message = "Authentication required")

private fun persistenceUnavailable2C2(): ServiceUnavailableApiException =
    ServiceUnavailableApiException(
        code = "persistence_unavailable",
        message = "Database persistence is not configured",
    )

private fun parseCanonicalUuidOrNull(raw: String?): UUID? {
    val trimmed = raw?.trim()
    if (trimmed.isNullOrEmpty()) return null
    return try {
        UUID.fromString(trimmed).takeIf { it.toString().equals(trimmed, ignoreCase = true) }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun ApplicationCall.parseUuidPathParam(name: String, errorCode: String, errorMessage: String): UUID =
    parseCanonicalUuidOrNull(parameters[name])
        ?: throw BadRequestApiException(code = errorCode, message = errorMessage)

private fun parseUuidBodyField(raw: String?, field: String): UUID =
    parseCanonicalUuidOrNull(raw)
        ?: throw DomainValidationException(listOf(ValidationViolation(field, "must be a valid canonical UUID")))

private fun ApplicationCall.parsePageRequest2C2(): PageRequest {
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

private fun CompanyInvitation.toResponse() = InvitationResponse(
    id = id,
    companyId = companyId,
    inviteeUserId = inviteeUserId,
    role = role.wireValue,
    status = status.wireValue,
    expiresAt = expiresAt,
    respondedAt = respondedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun CompanyMembership.toMembershipResponse() = MembershipResponse(
    companyId = companyId,
    userId = userId,
    role = role.wireValue,
    status = status.wireValue,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun ReplaceAdminResult.toResponse() = ReplaceAdminResponse(
    outgoingMembership = outgoingMembership.toMembershipResponse(),
    successorMembership = successorMembership.toMembershipResponse(),
)
