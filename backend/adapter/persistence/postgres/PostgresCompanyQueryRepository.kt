package com.nexora.backend.adapter.persistence.postgres

import com.nexora.backend.application.multitenant.CompanyQueryRepository
import com.nexora.backend.application.persistence.TransactionalSession
import com.nexora.backend.domain.multitenant.Company
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.CompanyRole
import com.nexora.backend.domain.multitenant.CompanyStatus
import com.nexora.backend.domain.multitenant.MembershipStatus
import com.nexora.backend.domain.multitenant.PageRequest
import com.nexora.backend.domain.multitenant.PagedResult
import com.nexora.backend.domain.multitenant.UserCompanySummary
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

class PostgresCompanyQueryRepository : CompanyQueryRepository {
    override fun listActiveCompaniesForUser(
        session: TransactionalSession,
        userId: UUID,
        page: PageRequest,
    ): PagedResult<UserCompanySummary> {
        val rows = session.connection.prepareStatement(LIST_ACTIVE_COMPANIES_SQL).use { stmt ->
            stmt.setString(1, userId.toString())
            stmt.setInt(2, page.limit + 1)
            stmt.setInt(3, page.offset)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            UserCompanySummary(
                                company = mapCompany(rs),
                                role = CompanyRole.fromDb(rs.getString("role")),
                                membershipStatus = MembershipStatus.fromDb(rs.getString("membership_status")),
                                joinedAt = rs.getObject("joined_at", OffsetDateTime::class.java).toInstant(),
                            ),
                        )
                    }
                }
            }
        }
        val hasMore = rows.size > page.limit
        return PagedResult(
            items = if (hasMore) rows.take(page.limit) else rows,
            limit = page.limit,
            offset = page.offset,
            hasMore = hasMore,
        )
    }

    override fun findActiveCompanyById(
        session: TransactionalSession,
        companyId: UUID,
    ): Company? = session.connection.prepareStatement(FIND_ACTIVE_COMPANY_SQL).use { stmt ->
        stmt.setString(1, companyId.toString())
        stmt.executeQuery().use { rs ->
            if (rs.next()) mapCompany(rs) else null
        }
    }

    override fun findActiveMembership(
        session: TransactionalSession,
        companyId: UUID,
        userId: UUID,
    ): CompanyMembership? = session.connection.prepareStatement(FIND_ACTIVE_MEMBERSHIP_SQL).use { stmt ->
        stmt.setString(1, companyId.toString())
        stmt.setString(2, userId.toString())
        stmt.executeQuery().use { rs ->
            if (rs.next()) mapMembership(rs) else null
        }
    }

    override fun listVisibleMemberships(
        session: TransactionalSession,
        companyId: UUID,
        page: PageRequest,
    ): PagedResult<CompanyMembership> {
        val rows = session.connection.prepareStatement(LIST_VISIBLE_MEMBERSHIPS_SQL).use { stmt ->
            stmt.setString(1, companyId.toString())
            stmt.setInt(2, page.limit + 1)
            stmt.setInt(3, page.offset)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(mapMembership(rs))
                    }
                }
            }
        }
        val hasMore = rows.size > page.limit
        return PagedResult(
            items = if (hasMore) rows.take(page.limit) else rows,
            limit = page.limit,
            offset = page.offset,
            hasMore = hasMore,
        )
    }

    private fun mapCompany(rs: ResultSet): Company = Company(
        id = UUID.fromString(rs.getString("id")),
        slug = rs.getString("slug"),
        name = rs.getString("name"),
        status = CompanyStatus.fromDb(rs.getString("status")),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
    )

    private fun mapMembership(rs: ResultSet): CompanyMembership = CompanyMembership(
        companyId = UUID.fromString(rs.getString("company_id")),
        userId = UUID.fromString(rs.getString("user_id")),
        role = CompanyRole.fromDb(rs.getString("role")),
        status = MembershipStatus.fromDb(rs.getString("status")),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
    )

    private companion object {
        const val LIST_ACTIVE_COMPANIES_SQL = """
            SELECT c.id, c.slug, c.name, c.status, c.created_at, c.updated_at,
                   m.role, m.status AS membership_status, m.created_at AS joined_at
            FROM nexora.companies c
            JOIN nexora.company_memberships m
              ON m.company_id = c.id
             AND m.user_id = ?::uuid
            WHERE c.status = 'active'
              AND m.status = 'active'
            ORDER BY c.name ASC, c.id ASC
            LIMIT ? OFFSET ?
        """

        const val FIND_ACTIVE_COMPANY_SQL = """
            SELECT id, slug, name, status, created_at, updated_at
            FROM nexora.companies
            WHERE id = ?::uuid
              AND status = 'active'
        """

        const val FIND_ACTIVE_MEMBERSHIP_SQL = """
            SELECT m.company_id, m.user_id, m.role, m.status, m.created_at, m.updated_at
            FROM nexora.company_memberships m
            JOIN nexora.companies c ON c.id = m.company_id
            WHERE m.company_id = ?::uuid
              AND m.user_id = ?::uuid
              AND c.status = 'active'
              AND m.status = 'active'
        """

        const val LIST_VISIBLE_MEMBERSHIPS_SQL = """
            SELECT m.company_id, m.user_id, m.role, m.status, m.created_at, m.updated_at
            FROM nexora.company_memberships m
            WHERE m.company_id = ?::uuid
            ORDER BY m.created_at ASC, m.user_id ASC
            LIMIT ? OFFSET ?
        """
    }
}
