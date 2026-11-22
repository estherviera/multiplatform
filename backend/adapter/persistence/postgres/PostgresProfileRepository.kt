package com.nexora.backend.adapter.persistence.postgres

import com.nexora.backend.application.multitenant.ProfileIdentityConflictException
import com.nexora.backend.application.multitenant.ProfileRepository
import com.nexora.backend.application.persistence.TransactionalSession
import com.nexora.backend.domain.multitenant.Profile
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID
import org.postgresql.util.PSQLException

class PostgresProfileRepository : ProfileRepository {
    override fun findById(session: TransactionalSession, userId: UUID): Profile? =
        session.connection.prepareStatement(FIND_BY_ID_SQL).use { stmt ->
            stmt.setString(1, userId.toString())
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapProfile(rs) else null
            }
        }

    override fun upsert(
        session: TransactionalSession,
        userId: UUID,
        displayName: String,
        avatarPath: String?,
    ): Profile = try {
        session.connection.prepareStatement(UPSERT_SQL).use { stmt ->
            stmt.setString(1, userId.toString())
            stmt.setString(2, displayName)
            if (avatarPath == null) {
                stmt.setNull(3, Types.VARCHAR)
            } else {
                stmt.setString(3, avatarPath)
            }
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw IllegalStateException("Profile upsert returned no row")
                }
                mapProfile(rs)
            }
        }
    } catch (ex: PSQLException) {
        if (ex.sqlState == SQLSTATE_FOREIGN_KEY_VIOLATION) {
            throw ProfileIdentityConflictException()
        }
        throw ex
    }

    private fun mapProfile(rs: ResultSet): Profile = Profile(
        id = UUID.fromString(rs.getString("id")),
        displayName = rs.getString("display_name"),
        avatarPath = rs.getString("avatar_path"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
    )

    private companion object {
        const val SQLSTATE_FOREIGN_KEY_VIOLATION = "23503"

        const val FIND_BY_ID_SQL = """
            SELECT id, display_name, avatar_path, created_at, updated_at
            FROM nexora.profiles
            WHERE id = ?::uuid
        """

        const val UPSERT_SQL = """
            INSERT INTO nexora.profiles (id, display_name, avatar_path)
            VALUES (?::uuid, ?, ?)
            ON CONFLICT (id) DO UPDATE
                SET display_name = EXCLUDED.display_name,
                    avatar_path = EXCLUDED.avatar_path,
                    updated_at = GREATEST(now(), nexora.profiles.created_at)
            RETURNING id, display_name, avatar_path, created_at, updated_at
        """
    }
}
