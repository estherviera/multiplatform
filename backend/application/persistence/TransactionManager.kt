package com.nexora.backend.application.persistence

import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import java.sql.Connection

class MissingIdentityContextException(
    message: String = "Authenticated principal is required for this transactional operation",
) : RuntimeException(message)

interface TransactionalSession {
    val connection: Connection
    val principal: AuthenticatedPrincipal?
}

interface TransactionManager {
    /**
     * Executes JDBC work inside an explicit transaction bound to a verified
     * [AuthenticatedPrincipal]. Sets PostgreSQL transaction-local `app.user_id`
     * via parameterized `set_config('app.user_id', ?, true)`.
     */
    suspend fun <T> inAuthenticatedTransaction(
        principal: AuthenticatedPrincipal,
        readOnly: Boolean = false,
        block: (TransactionalSession) -> T,
    ): T

    /**
     * Guarded entry point that fails closed with [MissingIdentityContextException]
     * before acquiring any database connection when [principal] is null.
     */
    suspend fun <T> requireAuthenticatedTransaction(
        principal: AuthenticatedPrincipal?,
        readOnly: Boolean = false,
        block: (TransactionalSession) -> T,
    ): T {
        val verified = principal ?: throw MissingIdentityContextException()
        return inAuthenticatedTransaction(verified, readOnly, block)
    }

    /**
     * Executes non-user infrastructure work (e.g., readiness check) inside an explicit
     * transaction with no user identity context (`app.user_id` cleared locally).
     */
    suspend fun <T> inInfrastructureTransaction(
        readOnly: Boolean = true,
        block: (TransactionalSession) -> T,
    ): T
}
