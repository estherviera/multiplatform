package com.nexora.backend.adapter.persistence.postgres

import com.nexora.backend.application.multitenant.DomainConflictException
import com.nexora.backend.application.multitenant.MembershipManagementRepository
import com.nexora.backend.application.persistence.TransactionalSession
import com.nexora.backend.domain.multitenant.CompanyInvitation
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.CompanyRole
import com.nexora.backend.domain.multitenant.InvitationStatus
import com.nexora.backend.domain.multitenant.MembershipStatus
import com.nexora.backend.domain.multitenant.PageRequest
import com.nexora.backend.domain.multitenant.PagedResult
import com.nexora.backend.domain.multitenant.ReplaceAdminResult
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.postgresql.util.PSQLException

class PostgresMembershipManagementRepository : MembershipManagementRepository {

    override fun lockActiveCompany(session: TransactionalSession, companyId: UUID): Boolean =
        session.connection.prepareStatement(LOCK_ACTIVE_COMPANY_SQL).use { stmt ->
            stmt.setString(1, companyId.toString())
            stmt.executeQuery().use { rs -> rs.next() }
        }

    override fun isProfileEligible(session: TransactionalSession, userId: UUID): Boolean =
        session.connection.prepareStatement(PROFILE_EXISTS_SQL).use { stmt ->
            stmt.setString(1, userId.toString())
            stmt.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }

    override fun findMembershipForUpdate(
        session: TransactionalSession,
        companyId: UUID,
        userId: UUID,
    ): CompanyMembership? = session.connection.prepareStatement(FIND_MEMBERSHIP_FOR_UPDATE_SQL).use { stmt ->
        stmt.setString(1, companyId.toString())
        stmt.setString(2, userId.toString())
        stmt.executeQuery().use { rs -> if (rs.next()) mapMembership(rs) else null }
    }

    override fun updateMembershipRole(
        session: TransactionalSession,
        companyId: UUID,
        userId: UUID,
        newRole: CompanyRole,
    ): CompanyMembership = session.connection.prepareStatement(UPDATE_MEMBERSHIP_ROLE_SQL).use { stmt ->
        stmt.setString(1, newRole.wireValue)
        stmt.setString(2, companyId.toString())
        stmt.setString(3, userId.toString())
        stmt.executeQuery().use { rs ->
            if (!rs.next()) throw IllegalStateException("Membership role update returned no row")
            mapMembership(rs)
        }
    }

    override fun updateMembershipStatus(
        session: TransactionalSession,
        companyId: UUID,
        userId: UUID,
        newStatus: MembershipStatus,
    ): CompanyMembership = session.connection.prepareStatement(UPDATE_MEMBERSHIP_STATUS_SQL).use { stmt ->
        stmt.setString(1, newStatus.wireValue)
        stmt.setString(2, companyId.toString())
        stmt.setString(3, userId.toString())
        stmt.executeQuery().use { rs ->
            if (!rs.next()) throw IllegalStateException("Membership status update returned no row")
            mapMembership(rs)
        }
    }

    override fun findPendingInvitationForUpdate(
        session: TransactionalSession,
        companyId: UUID,
        inviteeUserId: UUID,
    ): CompanyInvitation? = session.connection.prepareStatement(FIND_PENDING_INVITATION_FOR_UPDATE_SQL).use { stmt ->
        stmt.setString(1, companyId.toString())
        stmt.setString(2, inviteeUserId.toString())
        stmt.executeQuery().use { rs -> if (rs.next()) mapInvitation(rs) else null }
    }

    override fun createInvitation(
        session: TransactionalSession,
        companyId: UUID,
        inviteeUserId: UUID,
        invitedByUserId: UUID,
        role: CompanyRole,
        expiresAt: Instant,
    ): CompanyInvitation = try {
        session.connection.prepareStatement(INSERT_INVITATION_SQL).use { stmt ->
            stmt.setString(1, companyId.toString())
            stmt.setString(2, inviteeUserId.toString())
            stmt.setString(3, invitedByUserId.toString())
            stmt.setString(4, role.wireValue)
            stmt.setObject(5, expiresAt.atOffset(ZoneOffset.UTC))
            stmt.executeQuery().use { rs ->
                if (!rs.next()) throw IllegalStateException("Invitation insert returned no row")
                mapInvitation(rs)
            }
        }
    } catch (ex: PSQLException) {
        if (ex.sqlState == "23505") {
            throw DomainConflictException("invitation_conflict", "A pending invitation already exists for this user")
        }
        throw ex
    }

    override fun listCompanyInvitations(
        session: TransactionalSession,
        companyId: UUID,
        page: PageRequest,
    ): PagedResult<CompanyInvitation> {
        val rows = session.connection.prepareStatement(LIST_COMPANY_INVITATIONS_SQL).use { stmt ->
            stmt.setString(1, companyId.toString())
            stmt.setInt(2, page.limit + 1)
            stmt.setInt(3, page.offset)
            stmt.executeQuery().use { rs ->
                buildList { while (rs.next()) add(mapInvitation(rs)) }
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

    override fun listUserInvitations(
        session: TransactionalSession,
        userId: UUID,
        page: PageRequest,
    ): PagedResult<CompanyInvitation> {
        val rows = session.connection.prepareStatement(LIST_USER_INVITATIONS_SQL).use { stmt ->
            stmt.setString(1, userId.toString())
            stmt.setInt(2, page.limit + 1)
            stmt.setInt(3, page.offset)
            stmt.executeQuery().use { rs ->
                buildList { while (rs.next()) add(mapInvitation(rs)) }
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

    override fun findInvitationByIdForUser(
        session: TransactionalSession,
        invitationId: UUID,
        userId: UUID,
    ): CompanyInvitation? = session.connection.prepareStatement(FIND_INVITATION_FOR_USER_SQL).use { stmt ->
        stmt.setString(1, invitationId.toString())
        stmt.setString(2, userId.toString())
        stmt.executeQuery().use { rs -> if (rs.next()) mapInvitation(rs) else null }
    }

    override fun findInvitationForUpdate(
        session: TransactionalSession,
        invitationId: UUID,
    ): CompanyInvitation? = session.connection.prepareStatement(FIND_INVITATION_FOR_UPDATE_SQL).use { stmt ->
        stmt.setString(1, invitationId.toString())
        stmt.executeQuery().use { rs -> if (rs.next()) mapInvitation(rs) else null }
    }

    override fun declineInvitation(
        session: TransactionalSession,
        invitationId: UUID,
    ): CompanyInvitation = session.connection.prepareStatement(DECLINE_INVITATION_SQL).use { stmt ->
        stmt.setString(1, invitationId.toString())
        stmt.executeQuery().use { rs ->
            if (!rs.next()) throw DomainConflictException("invitation_not_pending", "Invitation can no longer be declined")
            mapInvitation(rs)
        }
    }

    override fun revokeInvitation(
        session: TransactionalSession,
        invitationId: UUID,
    ): CompanyInvitation = session.connection.prepareStatement(REVOKE_INVITATION_SQL).use { stmt ->
        stmt.setString(1, invitationId.toString())
        stmt.executeQuery().use { rs ->
            if (!rs.next()) throw DomainConflictException("invitation_not_pending", "Invitation can no longer be revoked")
            mapInvitation(rs)
        }
    }

    override fun acceptInvitationAtomically(
        session: TransactionalSession,
        invitationId: UUID,
        correlationId: String?,
    ): CompanyMembership? = try {
        session.connection.prepareStatement(ACCEPT_INVITATION_SQL).use { stmt ->
            stmt.setString(1, invitationId.toString())
            if (correlationId == null) {
                stmt.setNull(2, Types.VARCHAR)
            } else {
                stmt.setString(2, correlationId)
            }
            stmt.executeQuery().use { rs -> if (rs.next()) mapMembership(rs) else null }
        }
    } catch (ex: PSQLException) {
        if (ex.sqlState == "P0001") {
            val code = ex.serverErrorMessage?.message?.trim() ?: "invitation_conflict"
            val message = when (code) {
                "invitation_not_pending" -> "Invitation is no longer pending"
                "invitation_expired" -> "Invitation has expired"
                "membership_suspended" -> "Membership is suspended and must be reactivated by an administrator"
                "membership_already_active" -> "User is already an active member of this company"
                else -> "Invitation cannot be accepted"
            }
            throw DomainConflictException(code = code, message = message)
        }
        throw ex
    }

    override fun replaceAdminAtomically(
        session: TransactionalSession,
        companyId: UUID,
        outgoingAdminUserId: UUID,
        successorUserId: UUID,
        outgoingNewRole: CompanyRole,
        correlationId: String?,
    ): ReplaceAdminResult = try {
        setTransactionCorrelationId(session, correlationId)
        session.connection.prepareStatement(REPLACE_ADMIN_SQL).use { stmt ->
            stmt.setString(1, companyId.toString())
            stmt.setString(2, outgoingAdminUserId.toString())
            stmt.setString(3, successorUserId.toString())
            stmt.setString(4, outgoingNewRole.wireValue)
            if (correlationId == null) {
                stmt.setNull(5, Types.VARCHAR)
            } else {
                stmt.setString(5, correlationId)
            }
            var outgoing: CompanyMembership? = null
            var successor: CompanyMembership? = null
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    when (rs.getString("member_kind")) {
                        "outgoing" -> outgoing = mapMembership(rs)
                        "successor" -> successor = mapMembership(rs)
                    }
                }
            }
            ReplaceAdminResult(
                outgoingMembership = outgoing ?: throw IllegalStateException("Admin replacement returned no outgoing row"),
                successorMembership = successor ?: throw IllegalStateException("Admin replacement returned no successor row"),
            )
        }
    } catch (ex: PSQLException) {
        if (ex.sqlState == "P0001") {
            val code = ex.serverErrorMessage?.message?.trim() ?: "admin_replacement_conflict"
            val message = when (code) {
                "outgoing_not_active_admin" -> "Outgoing user is not an active company_admin of this company"
                "successor_not_active_member" -> "Successor must be an active member of this company"
                else -> "Administrator replacement cannot be completed"
            }
            throw DomainConflictException(code = code, message = message)
        }
        throw ex
    }

    override fun setTransactionCorrelationId(
        session: TransactionalSession,
        correlationId: String?,
    ) {
        val sanitized = correlationId?.trim()?.take(64).orEmpty()
        session.connection.prepareStatement(SET_CORRELATION_ID_SQL).use { stmt ->
            stmt.setString(1, sanitized)
            stmt.executeQuery().close()
        }
    }

    private fun mapMembership(rs: ResultSet): CompanyMembership = CompanyMembership(
        companyId = UUID.fromString(rs.getString("company_id")),
        userId = UUID.fromString(rs.getString("user_id")),
        role = CompanyRole.fromDb(rs.getString("role")),
        status = MembershipStatus.fromDb(rs.getString("status")),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
    )

    private fun mapInvitation(rs: ResultSet): CompanyInvitation = CompanyInvitation(
        id = UUID.fromString(rs.getString("id")),
        companyId = UUID.fromString(rs.getString("company_id")),
        inviteeUserId = UUID.fromString(rs.getString("invitee_user_id")),
        invitedByUserId = UUID.fromString(rs.getString("invited_by_user_id")),
        role = CompanyRole.fromDb(rs.getString("role")),
        status = InvitationStatus.fromDb(rs.getString("status")),
        expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
        respondedAt = rs.getObject("responded_at", OffsetDateTime::class.java)?.toInstant(),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
    )

    private companion object {
        const val LOCK_ACTIVE_COMPANY_SQL = """
            SELECT id FROM nexora.companies WHERE id = ?::uuid AND status = 'active' FOR UPDATE
        """

        const val PROFILE_EXISTS_SQL = "SELECT nexora.profile_exists(?::uuid)"

        const val FIND_MEMBERSHIP_FOR_UPDATE_SQL = """
            SELECT company_id, user_id, role, status, created_at, updated_at
            FROM nexora.company_memberships
            WHERE company_id = ?::uuid AND user_id = ?::uuid
            FOR UPDATE
        """

        const val UPDATE_MEMBERSHIP_ROLE_SQL = """
            UPDATE nexora.company_memberships
            SET role = ?, updated_at = GREATEST(now(), created_at)
            WHERE company_id = ?::uuid AND user_id = ?::uuid
            RETURNING company_id, user_id, role, status, created_at, updated_at
        """

        const val UPDATE_MEMBERSHIP_STATUS_SQL = """
            UPDATE nexora.company_memberships
            SET status = ?, updated_at = GREATEST(now(), created_at)
            WHERE company_id = ?::uuid AND user_id = ?::uuid
            RETURNING company_id, user_id, role, status, created_at, updated_at
        """

        const val FIND_PENDING_INVITATION_FOR_UPDATE_SQL = """
            SELECT id, company_id, invitee_user_id, invited_by_user_id, role, status, expires_at, responded_at, created_at, updated_at
            FROM nexora.company_invitations
            WHERE company_id = ?::uuid AND invitee_user_id = ?::uuid AND status = 'pending'
            FOR UPDATE
        """

        const val INSERT_INVITATION_SQL = """
            INSERT INTO nexora.company_invitations (company_id, invitee_user_id, invited_by_user_id, role, expires_at)
            VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?)
            RETURNING id, company_id, invitee_user_id, invited_by_user_id, role, status, expires_at, responded_at, created_at, updated_at
        """

        const val LIST_COMPANY_INVITATIONS_SQL = """
            SELECT id, company_id, invitee_user_id, invited_by_user_id, role, status, expires_at, responded_at, created_at, updated_at
            FROM nexora.company_invitations
            WHERE company_id = ?::uuid
            ORDER BY created_at DESC, id ASC
            LIMIT ? OFFSET ?
        """

        const val LIST_USER_INVITATIONS_SQL = """
            SELECT id, company_id, invitee_user_id, invited_by_user_id, role, status, expires_at, responded_at, created_at, updated_at
            FROM nexora.company_invitations
            WHERE invitee_user_id = ?::uuid
            ORDER BY created_at DESC, id ASC
            LIMIT ? OFFSET ?
        """

        const val FIND_INVITATION_FOR_USER_SQL = """
            SELECT id, company_id, invitee_user_id, invited_by_user_id, role, status, expires_at, responded_at, created_at, updated_at
            FROM nexora.company_invitations
            WHERE id = ?::uuid AND invitee_user_id = ?::uuid
        """

        const val FIND_INVITATION_FOR_UPDATE_SQL = """
            SELECT id, company_id, invitee_user_id, invited_by_user_id, role, status, expires_at, responded_at, created_at, updated_at
            FROM nexora.company_invitations
            WHERE id = ?::uuid
            FOR UPDATE
        """

        const val DECLINE_INVITATION_SQL = """
            UPDATE nexora.company_invitations
            SET status = 'declined', responded_at = now(), updated_at = GREATEST(now(), created_at)
            WHERE id = ?::uuid AND status = 'pending'
            RETURNING id, company_id, invitee_user_id, invited_by_user_id, role, status, expires_at, responded_at, created_at, updated_at
        """

        const val REVOKE_INVITATION_SQL = """
            UPDATE nexora.company_invitations
            SET status = 'revoked', responded_at = now(), updated_at = GREATEST(now(), created_at)
            WHERE id = ?::uuid AND status = 'pending'
            RETURNING id, company_id, invitee_user_id, invited_by_user_id, role, status, expires_at, responded_at, created_at, updated_at
        """

        const val ACCEPT_INVITATION_SQL = """
            SELECT company_id, user_id, role, status, created_at, updated_at
            FROM nexora.accept_company_invitation(?::uuid, ?)
        """

        const val REPLACE_ADMIN_SQL = """
            SELECT member_kind, company_id, user_id, role, status, created_at, updated_at
            FROM nexora.replace_company_admin(?::uuid, ?::uuid, ?::uuid, ?, ?)
        """

        const val SET_CORRELATION_ID_SQL = "SELECT set_config('app.correlation_id', ?, true)"
    }
}
