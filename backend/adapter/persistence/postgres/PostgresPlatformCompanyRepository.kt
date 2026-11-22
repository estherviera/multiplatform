package com.nexora.backend.adapter.persistence.postgres

import com.nexora.backend.application.multitenant.DomainConflictException
import com.nexora.backend.application.multitenant.DomainValidationException
import com.nexora.backend.application.multitenant.ValidationViolation
import com.nexora.backend.application.platform.PlatformCompanyCreationResult
import com.nexora.backend.application.platform.PlatformCompanyRepository
import com.nexora.backend.domain.multitenant.Company
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.CompanyRole
import com.nexora.backend.domain.multitenant.CompanyStatus
import com.nexora.backend.domain.multitenant.MembershipStatus
import java.sql.Connection
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.withContext
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory

/**
 * PostgreSQL adapter for platform company creation and platform audit recording.
 *
 * Connects EXCLUSIVELY through `PostgresPlatformPool` (`app_platform`), which has zero direct
 * table privileges and can only invoke the fixed SQL functions in `nexora_platform`.
 */
class PostgresPlatformCompanyRepository(
    private val platformPool: PostgresPlatformPool,
) : PlatformCompanyRepository {

    override suspend fun createCompanyWithInitialAdmin(
        actorUserId: UUID,
        idempotencyKey: String,
        requestHash: String,
        slug: String,
        name: String,
        initialAdminUserId: UUID,
        correlationId: String?,
    ): PlatformCompanyCreationResult = inPlatformTransaction { conn ->
        try {
            conn.prepareStatement(CREATE_COMPANY_SQL).use { stmt ->
                stmt.setString(1, actorUserId.toString())
                stmt.setString(2, idempotencyKey)
                stmt.setString(3, requestHash)
                stmt.setString(4, slug)
                stmt.setString(5, name)
                stmt.setString(6, initialAdminUserId.toString())
                if (correlationId == null) {
                    stmt.setNull(7, Types.VARCHAR)
                } else {
                    stmt.setString(7, correlationId)
                }

                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        throw IllegalStateException("Platform company creation returned no row")
                    }
                    val replayed = rs.getBoolean("replayed")
                    val companyId = UUID.fromString(rs.getString("company_id"))
                    val company = Company(
                        id = companyId,
                        slug = rs.getString("company_slug"),
                        name = rs.getString("company_name"),
                        status = CompanyStatus.fromDb(rs.getString("company_status")),
                        createdAt = rs.getObject("company_created_at", OffsetDateTime::class.java).toInstant(),
                        updatedAt = rs.getObject("company_updated_at", OffsetDateTime::class.java).toInstant(),
                    )
                    val membership = CompanyMembership(
                        companyId = companyId,
                        userId = UUID.fromString(rs.getString("admin_user_id")),
                        role = CompanyRole.fromDb(rs.getString("admin_role")),
                        status = MembershipStatus.fromDb(rs.getString("admin_status")),
                        createdAt = rs.getObject("admin_created_at", OffsetDateTime::class.java).toInstant(),
                        updatedAt = rs.getObject("admin_updated_at", OffsetDateTime::class.java).toInstant(),
                    )
                    PlatformCompanyCreationResult(
                        company = company,
                        initialAdminMembership = membership,
                        idempotencyReplayed = replayed,
                    )
                }
            }
        } catch (ex: PSQLException) {
            mapPlatformSqlError(ex)
        }
    }

    override suspend fun recordPlatformAuditEvent(
        actorUserId: UUID?,
        action: String,
        targetType: String,
        targetId: String,
        outcome: String,
        correlationId: String?,
        metadataJson: String,
    ) {
        inPlatformTransaction { conn ->
            conn.prepareStatement(RECORD_PLATFORM_AUDIT_SQL).use { stmt ->
                if (actorUserId == null) {
                    stmt.setNull(1, Types.VARCHAR)
                } else {
                    stmt.setString(1, actorUserId.toString())
                }
                stmt.setString(2, action)
                stmt.setString(3, targetType)
                stmt.setString(4, targetId)
                stmt.setString(5, outcome)
                if (correlationId == null) {
                    stmt.setNull(6, Types.VARCHAR)
                } else {
                    stmt.setString(6, correlationId)
                }
                stmt.setString(7, metadataJson)
                stmt.executeQuery().close()
            }
        }
    }

    private suspend fun <T> inPlatformTransaction(block: (Connection) -> T): T =
        withContext(platformPool.ioDispatcher) {
            val connection = platformPool.dataSource.connection
            var evict = false
            try {
                if (connection.autoCommit) {
                    connection.autoCommit = false
                }
                connection.createStatement().use { stmt ->
                    stmt.execute("SET TRANSACTION READ WRITE")
                }
                val result = block(connection)
                connection.commit()
                result
            } catch (ex: Throwable) {
                try {
                    connection.rollback()
                } catch (rbEx: Throwable) {
                    evict = true
                    logger.error("Platform transaction rollback failed; evicting connection [exception={}]", rbEx::class.java.simpleName)
                }
                if (ex is PSQLException && (ex.sqlState?.startsWith("08") == true || ex.sqlState == "57014")) {
                    evict = true
                }
                throw ex
            } finally {
                if (evict) {
                    runCatching { platformPool.evictConnection(connection) }
                }
                runCatching { connection.close() }
            }
        }

    private fun mapPlatformSqlError(ex: PSQLException): Nothing {
        if (ex.sqlState == "23505") {
            throw DomainConflictException(
                code = "company_slug_conflict",
                message = "A company with this slug already exists",
            )
        }
        if (ex.sqlState == "P0001") {
            val code = ex.serverErrorMessage?.message?.trim() ?: "platform_conflict"
            when (code) {
                "idempotency_key_conflict" -> throw DomainConflictException(
                    code = "idempotency_key_conflict",
                    message = "Idempotency key was already used with a different request payload",
                )
                "initial_admin_not_eligible" -> throw DomainConflictException(
                    code = "initial_admin_not_eligible",
                    message = "Initial administrator user is not eligible (profile does not exist)",
                )
                "invalid_company_slug" -> throw DomainValidationException(
                    listOf(ValidationViolation("slug", "invalid company slug format")),
                )
                "invalid_company_name" -> throw DomainValidationException(
                    listOf(ValidationViolation("name", "invalid company name")),
                )
                "invalid_idempotency_key" -> throw DomainValidationException(
                    listOf(ValidationViolation("Idempotency-Key", "invalid idempotency key")),
                )
                else -> throw DomainConflictException(code = code, message = "Platform company operation failed")
            }
        }
        throw ex
    }

    private companion object {
        const val CREATE_COMPANY_SQL = """
            SELECT
                replayed,
                company_id,
                company_slug,
                company_name,
                company_status,
                company_created_at,
                company_updated_at,
                admin_user_id,
                admin_role,
                admin_status,
                admin_created_at,
                admin_updated_at
            FROM nexora_platform.create_company_with_initial_admin(
                ?::uuid, ?, ?, ?, ?, ?::uuid, ?
            )
        """

        const val RECORD_PLATFORM_AUDIT_SQL = """
            SELECT nexora_platform.record_platform_audit_event(
                ?::uuid, ?, ?, ?, ?, ?, ?::jsonb
            )
        """

        private val logger = LoggerFactory.getLogger("com.nexora.backend.adapter.persistence.postgres.PostgresPlatformCompanyRepository")
    }
}
