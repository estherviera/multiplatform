package com.nexora.backend.application.multitenant

import com.nexora.backend.application.persistence.TransactionManager
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import com.nexora.backend.domain.multitenant.Company
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.PageRequest
import com.nexora.backend.domain.multitenant.PagedResult
import com.nexora.backend.domain.multitenant.UserCompanySummary
import java.util.UUID

class CompanyQueryService(
    private val transactionManager: TransactionManager,
    private val companyQueryRepository: CompanyQueryRepository,
) {
    suspend fun listMyCompanies(
        principal: AuthenticatedPrincipal,
        page: PageRequest,
    ): PagedResult<UserCompanySummary> =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = true) { session ->
            companyQueryRepository.listActiveCompaniesForUser(session, principal.userId, page)
        }

    suspend fun getCompany(
        principal: AuthenticatedPrincipal,
        companyId: UUID,
    ): Company =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = true) { session ->
            companyQueryRepository.findActiveCompanyById(session, companyId)
                ?: throw ResourceNotFoundException(
                    code = "company_not_found",
                    message = "Company not found",
                )
        }

    suspend fun getMyMembership(
        principal: AuthenticatedPrincipal,
        companyId: UUID,
    ): CompanyMembership =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = true) { session ->
            companyQueryRepository.findActiveMembership(session, companyId, principal.userId)
                ?: throw ResourceNotFoundException(
                    code = "company_not_found",
                    message = "Company not found",
                )
        }

    suspend fun listVisibleMemberships(
        principal: AuthenticatedPrincipal,
        companyId: UUID,
        page: PageRequest,
    ): PagedResult<CompanyMembership> =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = true) { session ->
            // Verify company visibility under RLS first so foreign, suspended, or nonexistent
            // companies consistently return 404 without leaking existence.
            companyQueryRepository.findActiveCompanyById(session, companyId)
                ?: throw ResourceNotFoundException(
                    code = "company_not_found",
                    message = "Company not found",
                )

            companyQueryRepository.listVisibleMemberships(session, companyId, page)
        }
}
