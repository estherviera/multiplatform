package com.nexora.backend.application.multitenant

import com.nexora.backend.application.persistence.TransactionalSession
import com.nexora.backend.domain.multitenant.Company
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.PageRequest
import com.nexora.backend.domain.multitenant.PagedResult
import com.nexora.backend.domain.multitenant.Profile
import com.nexora.backend.domain.multitenant.UserCompanySummary
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
