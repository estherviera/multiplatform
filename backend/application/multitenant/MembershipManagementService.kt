package com.nexora.backend.application.multitenant

import com.nexora.backend.application.persistence.TransactionManager
import com.nexora.backend.application.persistence.TransactionalSession
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import com.nexora.backend.domain.multitenant.CompanyInvitation
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.CompanyRole
import com.nexora.backend.domain.multitenant.InvitationStatus
import com.nexora.backend.domain.multitenant.MembershipStatus
import com.nexora.backend.domain.multitenant.PageRequest
import com.nexora.backend.domain.multitenant.PagedResult
import com.nexora.backend.domain.multitenant.ReplaceAdminResult
import java.time.Clock
import java.time.Duration
import java.util.UUID

data class CreateInvitationOutcome(
    val invitation: CompanyInvitation,
    val created: Boolean,
)

class MembershipManagementService(
    private val transactionManager: TransactionManager,
    private val companyQueryRepository: CompanyQueryRepository,
    private val managementRepository: MembershipManagementRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun createInvitation(
        principal: AuthenticatedPrincipal,
        companyId: UUID,
        inviteeUserId: UUID,
        rawRole: String?,
        correlationId: String?,
    ): CreateInvitationOutcome {
        val role = parseRole(rawRole, "role")
        return transactionManager.inAuthenticatedTransaction(principal, readOnly = false) { session ->
            requireActiveCompanyAdmin(session, principal, companyId)
            if (!managementRepository.lockActiveCompany(session, companyId)) {
                throw ResourceNotFoundException("company_not_found", "Company not found")
            }

            if (!managementRepository.isProfileEligible(session, inviteeUserId)) {
                throw DomainConflictException(
                    code = "invitee_not_eligible",
                    message = "Target user is not eligible to be invited",
                )
            }

            val existingMembership = managementRepository.findMembershipForUpdate(session, companyId, inviteeUserId)
            if (existingMembership != null && existingMembership.status != MembershipStatus.INVITED) {
                throw DomainConflictException(
                    code = "membership_already_exists",
                    message = "User already has a membership in this company",
                )
            }

            val now = clock.instant()
            val pending = managementRepository.findPendingInvitationForUpdate(session, companyId, inviteeUserId)
            if (pending != null) {
                if (pending.expiresAt.isAfter(now)) {
                    if (pending.role == role) {
                        return@inAuthenticatedTransaction CreateInvitationOutcome(invitation = pending, created = false)
                    }
                    throw DomainConflictException(
                        code = "invitation_conflict",
                        message = "A pending invitation with a different role already exists for this user",
                    )
                }
                managementRepository.revokeInvitation(session, pending.id)
            }

            managementRepository.setTransactionCorrelationId(session, correlationId)
            val invitation = managementRepository.createInvitation(
                session = session,
                companyId = companyId,
                inviteeUserId = inviteeUserId,
                invitedByUserId = principal.userId,
                role = role,
                expiresAt = now.plus(DEFAULT_INVITATION_TTL),
            )

            CreateInvitationOutcome(invitation = invitation, created = true)
        }
    }

    suspend fun listCompanyInvitations(
        principal: AuthenticatedPrincipal,
        companyId: UUID,
        page: PageRequest,
    ): PagedResult<CompanyInvitation> =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = true) { session ->
            requireActiveCompanyAdmin(session, principal, companyId)
            managementRepository.listCompanyInvitations(session, companyId, page)
        }

    suspend fun listMyInvitations(
        principal: AuthenticatedPrincipal,
        page: PageRequest,
    ): PagedResult<CompanyInvitation> =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = true) { session ->
            managementRepository.listUserInvitations(session, principal.userId, page)
        }

    suspend fun getMyInvitation(
        principal: AuthenticatedPrincipal,
        invitationId: UUID,
    ): CompanyInvitation =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = true) { session ->
            managementRepository.findInvitationByIdForUser(session, invitationId, principal.userId)
                ?: throw ResourceNotFoundException("invitation_not_found", "Invitation not found")
        }

    suspend fun acceptInvitation(
        principal: AuthenticatedPrincipal,
        invitationId: UUID,
        correlationId: String?,
    ): CompanyMembership =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = false) { session ->
            managementRepository.acceptInvitationAtomically(session, invitationId, correlationId)
                ?: throw ResourceNotFoundException("invitation_not_found", "Invitation not found")
        }

    suspend fun declineInvitation(
        principal: AuthenticatedPrincipal,
        invitationId: UUID,
        correlationId: String?,
    ): CompanyInvitation =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = false) { session ->
            // Lock pending row under FOR UPDATE RLS; if already non-pending (declined/accepted/revoked),
            // PostgreSQL's pending-only FOR UPDATE policy filters it out, so fall back to plain SELECT.
            val invitation = managementRepository.findInvitationForUpdate(session, invitationId)
                ?: managementRepository.findInvitationByIdForUser(session, invitationId, principal.userId)
            if (invitation == null || invitation.inviteeUserId != principal.userId) {
                throw ResourceNotFoundException("invitation_not_found", "Invitation not found")
            }

            if (invitation.status == InvitationStatus.DECLINED) {
                return@inAuthenticatedTransaction invitation
            }
            if (invitation.status != InvitationStatus.PENDING) {
                throw DomainConflictException("invitation_not_pending", "Invitation can no longer be declined")
            }

            managementRepository.setTransactionCorrelationId(session, correlationId)
            managementRepository.declineInvitation(session, invitationId)
        }

    suspend fun changeMemberRole(
        principal: AuthenticatedPrincipal,
        companyId: UUID,
        targetUserId: UUID,
        rawNewRole: String?,
        correlationId: String?,
    ): CompanyMembership {
        val newRole = parseRole(rawNewRole, "role")
        return transactionManager.inAuthenticatedTransaction(principal, readOnly = false) { session ->
            requireActiveCompanyAdmin(session, principal, companyId)
            if (!managementRepository.lockActiveCompany(session, companyId)) {
                throw ResourceNotFoundException("company_not_found", "Company not found")
            }

            val target = managementRepository.findMembershipForUpdate(session, companyId, targetUserId)
                ?: throw ResourceNotFoundException("membership_not_found", "Membership not found")

            if (target.role == newRole) {
                return@inAuthenticatedTransaction target
            }

            managementRepository.setTransactionCorrelationId(session, correlationId)
            managementRepository.updateMembershipRole(session, companyId, targetUserId, newRole)
        }
    }

    suspend fun suspendMember(
        principal: AuthenticatedPrincipal,
        companyId: UUID,
        targetUserId: UUID,
        correlationId: String?,
    ): CompanyMembership =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = false) { session ->
            requireActiveCompanyAdmin(session, principal, companyId)
            if (!managementRepository.lockActiveCompany(session, companyId)) {
                throw ResourceNotFoundException("company_not_found", "Company not found")
            }

            val target = managementRepository.findMembershipForUpdate(session, companyId, targetUserId)
                ?: throw ResourceNotFoundException("membership_not_found", "Membership not found")

            if (target.status == MembershipStatus.SUSPENDED) {
                return@inAuthenticatedTransaction target
            }
            if (target.status != MembershipStatus.ACTIVE) {
                throw DomainConflictException("membership_not_active", "Only active memberships can be suspended")
            }

            managementRepository.setTransactionCorrelationId(session, correlationId)
            managementRepository.updateMembershipStatus(session, companyId, targetUserId, MembershipStatus.SUSPENDED)
        }

    suspend fun reactivateMember(
        principal: AuthenticatedPrincipal,
        companyId: UUID,
        targetUserId: UUID,
        correlationId: String?,
    ): CompanyMembership =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = false) { session ->
            requireActiveCompanyAdmin(session, principal, companyId)
            if (!managementRepository.lockActiveCompany(session, companyId)) {
                throw ResourceNotFoundException("company_not_found", "Company not found")
            }

            val target = managementRepository.findMembershipForUpdate(session, companyId, targetUserId)
                ?: throw ResourceNotFoundException("membership_not_found", "Membership not found")

            if (target.status == MembershipStatus.ACTIVE) {
                return@inAuthenticatedTransaction target
            }
            if (target.status != MembershipStatus.SUSPENDED) {
                throw DomainConflictException(
                    code = "membership_not_suspended",
                    message = "Only suspended memberships can be reactivated",
                )
            }

            managementRepository.setTransactionCorrelationId(session, correlationId)
            managementRepository.updateMembershipStatus(session, companyId, targetUserId, MembershipStatus.ACTIVE)
        }

    suspend fun replaceAdmin(
        principal: AuthenticatedPrincipal,
        companyId: UUID,
        outgoingAdminUserId: UUID,
        successorUserId: UUID,
        rawOutgoingNewRole: String?,
        correlationId: String?,
    ): ReplaceAdminResult {
        val outgoingNewRole = parseRole(rawOutgoingNewRole ?: CompanyRole.MEMBER.wireValue, "outgoingNewRole")
        val violations = mutableListOf<ValidationViolation>()
        if (outgoingAdminUserId == successorUserId) {
            violations.add(ValidationViolation("successorUserId", "must be different from outgoingAdminUserId"))
        }
        if (outgoingNewRole == CompanyRole.COMPANY_ADMIN) {
            violations.add(ValidationViolation("outgoingNewRole", "must be 'trainer' or 'member'"))
        }
        if (violations.isNotEmpty()) {
            throw DomainValidationException(violations)
        }

        return transactionManager.inAuthenticatedTransaction(principal, readOnly = false) { session ->
            requireActiveCompanyAdmin(session, principal, companyId)
            managementRepository.replaceAdminAtomically(
                session = session,
                companyId = companyId,
                outgoingAdminUserId = outgoingAdminUserId,
                successorUserId = successorUserId,
                outgoingNewRole = outgoingNewRole,
                correlationId = correlationId,
            )
        }
    }

    private fun requireActiveCompanyAdmin(
        session: TransactionalSession,
        principal: AuthenticatedPrincipal,
        companyId: UUID,
    ) {
        val membership = companyQueryRepository.findActiveMembership(session, companyId, principal.userId)
            ?: throw ResourceNotFoundException("company_not_found", "Company not found")
        if (membership.role != CompanyRole.COMPANY_ADMIN) {
            throw ForbiddenOperationException(
                code = "company_admin_required",
                message = "Active company_admin role is required",
            )
        }
    }

    private fun parseRole(raw: String?, field: String): CompanyRole {
        val normalized = raw?.trim()?.lowercase()
        val parsed = CompanyRole.entries.firstOrNull { it.wireValue == normalized }
        return parsed ?: throw DomainValidationException(
            listOf(ValidationViolation(field, "must be one of: company_admin, trainer, member")),
        )
    }

    companion object {
        val DEFAULT_INVITATION_TTL: Duration = Duration.ofDays(7)
    }
}
