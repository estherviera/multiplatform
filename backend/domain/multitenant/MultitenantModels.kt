package com.nexora.backend.domain.multitenant

import java.time.Instant
import java.util.UUID

data class Profile(
    val id: UUID,
    val displayName: String,
    val avatarPath: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class CompanyStatus(val wireValue: String) {
    ACTIVE("active"),
    SUSPENDED("suspended");

    companion object {
        fun fromDb(raw: String): CompanyStatus =
            entries.firstOrNull { it.wireValue == raw }
                ?: throw IllegalStateException("Unexpected company status '$raw'")
    }
}

data class Company(
    val id: UUID,
    val slug: String,
    val name: String,
    val status: CompanyStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class CompanyRole(val wireValue: String) {
    COMPANY_ADMIN("company_admin"),
    TRAINER("trainer"),
    MEMBER("member");

    companion object {
        fun fromDb(raw: String): CompanyRole =
            entries.firstOrNull { it.wireValue == raw }
                ?: throw IllegalStateException("Unexpected company role '$raw'")
    }
}

enum class MembershipStatus(val wireValue: String) {
    INVITED("invited"),
    ACTIVE("active"),
    SUSPENDED("suspended");

    companion object {
        fun fromDb(raw: String): MembershipStatus =
            entries.firstOrNull { it.wireValue == raw }
                ?: throw IllegalStateException("Unexpected membership status '$raw'")
    }
}

data class CompanyMembership(
    val companyId: UUID,
    val userId: UUID,
    val role: CompanyRole,
    val status: MembershipStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class UserCompanySummary(
    val company: Company,
    val role: CompanyRole,
    val membershipStatus: MembershipStatus,
    val joinedAt: Instant,
)

enum class InvitationStatus(val wireValue: String) {
    PENDING("pending"),
    ACCEPTED("accepted"),
    DECLINED("declined"),
    REVOKED("revoked");

    companion object {
        fun fromDb(raw: String): InvitationStatus =
            entries.firstOrNull { it.wireValue == raw }
                ?: throw IllegalStateException("Unexpected invitation status '$raw'")
    }
}

data class CompanyInvitation(
    val id: UUID,
    val companyId: UUID,
    val inviteeUserId: UUID,
    val invitedByUserId: UUID,
    val role: CompanyRole,
    val status: InvitationStatus,
    val expiresAt: Instant,
    val respondedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ReplaceAdminResult(
    val outgoingMembership: CompanyMembership,
    val successorMembership: CompanyMembership,
)

data class PageRequest(
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = DEFAULT_OFFSET,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        require(offset in 0..MAX_OFFSET) { "offset must be between 0 and $MAX_OFFSET" }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val DEFAULT_OFFSET = 0
        const val MAX_OFFSET = 10_000
    }
}

data class PagedResult<T>(
    val items: List<T>,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean,
)
