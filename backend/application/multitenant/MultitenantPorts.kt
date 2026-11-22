package com.nexora.backend.application.multitenant

import com.nexora.backend.application.persistence.TransactionalSession
import com.nexora.backend.domain.multitenant.Company
import com.nexora.backend.domain.multitenant.CompanyInvitation
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.CompanyRole
import com.nexora.backend.domain.multitenant.MembershipStatus
import com.nexora.backend.domain.multitenant.PageRequest
import com.nexora.backend.domain.multitenant.PagedResult
import com.nexora.backend.domain.multitenant.Profile
import com.nexora.backend.domain.multitenant.ReplaceAdminResult
import com.nexora.backend.domain.multitenant.UserCompanySummary
import java.time.Instant
import java.util.UUID

data class ValidationViolation(
    val field: String,
    val reason: String,
)

class DomainValidationException(
    val violations: List<ValidationViolation>,
    message: String = "Validation failed",
) : RuntimeException(message)

class ResourceNotFoundException(
    val code: String,
    override val message: String,
) : RuntimeException(message)

class ForbiddenOperationException(
    val code: String = "forbidden",
    override val message: String = "Access denied",
) : RuntimeException(message)

class DomainConflictException(
    val code: String,
    override val message: String,
) : RuntimeException(message)

class ProfileIdentityConflictException(
    override val message: String = "Authenticated identity is not eligible for profile provisioning",
) : RuntimeException(message)

interface ProfileRepository {
    fun findById(session: TransactionalSession, userId: UUID): Profile?
    fun upsert(session: TransactionalSession, userId: UUID, displayName: String, avatarPath: String?): Profile
}

interface CompanyQueryRepository {
    fun listActiveCompaniesForUser(
        session: TransactionalSession,
        userId: UUID,
        page: PageRequest,
    ): PagedResult<UserCompanySummary>

    fun findActiveCompanyById(
        session: TransactionalSession,
        companyId: UUID,
    ): Company?

    fun findActiveMembership(
        session: TransactionalSession,
        companyId: UUID,
        userId: UUID,
    ): CompanyMembership?

    fun listVisibleMemberships(
        session: TransactionalSession,
        companyId: UUID,
        page: PageRequest,
    ): PagedResult<CompanyMembership>
}

interface MembershipManagementRepository {
    fun lockActiveCompany(session: TransactionalSession, companyId: UUID): Boolean
    fun isProfileEligible(session: TransactionalSession, userId: UUID): Boolean
    fun findMembershipForUpdate(session: TransactionalSession, companyId: UUID, userId: UUID): CompanyMembership?
    fun updateMembershipRole(session: TransactionalSession, companyId: UUID, userId: UUID, newRole: CompanyRole): CompanyMembership
    fun updateMembershipStatus(session: TransactionalSession, companyId: UUID, userId: UUID, newStatus: MembershipStatus): CompanyMembership

    fun findPendingInvitationForUpdate(session: TransactionalSession, companyId: UUID, inviteeUserId: UUID): CompanyInvitation?
    fun createInvitation(
        session: TransactionalSession,
        companyId: UUID,
        inviteeUserId: UUID,
        invitedByUserId: UUID,
        role: CompanyRole,
        expiresAt: Instant,
    ): CompanyInvitation
    fun listCompanyInvitations(session: TransactionalSession, companyId: UUID, page: PageRequest): PagedResult<CompanyInvitation>
    fun listUserInvitations(session: TransactionalSession, userId: UUID, page: PageRequest): PagedResult<CompanyInvitation>
    fun findInvitationByIdForUser(session: TransactionalSession, invitationId: UUID, userId: UUID): CompanyInvitation?
    fun findInvitationForUpdate(session: TransactionalSession, invitationId: UUID): CompanyInvitation?
    fun declineInvitation(session: TransactionalSession, invitationId: UUID): CompanyInvitation
    fun revokeInvitation(session: TransactionalSession, invitationId: UUID): CompanyInvitation
    fun acceptInvitationAtomically(session: TransactionalSession, invitationId: UUID, correlationId: String?): CompanyMembership?

    fun replaceAdminAtomically(
        session: TransactionalSession,
        companyId: UUID,
        outgoingAdminUserId: UUID,
        successorUserId: UUID,
        outgoingNewRole: CompanyRole,
        correlationId: String?,
    ): ReplaceAdminResult

    fun setTransactionCorrelationId(
        session: TransactionalSession,
        correlationId: String?,
    )
}
