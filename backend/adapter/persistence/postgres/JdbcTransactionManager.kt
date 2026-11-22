package com.nexora.backend.adapter.persistence.postgres

import com.nexora.backend.application.persistence.TransactionManager
import com.nexora.backend.application.persistence.TransactionalSession
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import java.sql.Connection
import kotlinx.coroutines.withContext

private class JdbcTransactionalSession(
    override val connection: Connection,
    override val principal: AuthenticatedPrincipal?,
) : TransactionalSession

class JdbcTransactionManager(
    private val pool: PostgresRuntimePool,
) : TransactionManager {

    override suspend fun <T> inAuthenticatedTransaction(
        principal: AuthenticatedPrincipal,
        readOnly: Boolean,
        block: (TransactionalSession) -> T,
    ): T = executeTransaction(principal = principal, readOnly = readOnly, block = block)

    override suspend fun <T> inInfrastructureTransaction(
        readOnly: Boolean,
        block: (TransactionalSession) -> T,
    ): T = executeTransaction(principal = null, readOnly = readOnly, block = block)

    private suspend fun <T> executeTransaction(
        principal: AuthenticatedPrincipal?,
        readOnly: Boolean,
        block: (TransactionalSession) -> T,
    ): T = withContext(pool.ioDispatcher) {
        val connection = pool.dataSource.connection
        var evict = false
        try {
            if (connection.autoCommit) {
                connection.autoCommit = false
            }

            // Explicit transaction-local read-only / read-write mode (automatically resets on COMMIT/ROLLBACK;
            // avoids mutating session-level JDBC connection state across HikariCP reuses).
            val modeSql = if (readOnly) SET_TX_READ_ONLY_SQL else SET_TX_READ_WRITE_SQL
            connection.createStatement().use { stmt ->
                stmt.execute(modeSql)
            }

            // Binds transaction-local `app.user_id` exclusively from the verified AuthenticatedPrincipal
            // (or clears it locally for non-user infrastructure transactions).
            val contextValue = principal?.userId?.toString() ?: ""
            connection.prepareStatement(SET_LOCAL_USER_ID_SQL).use { stmt ->
                stmt.setString(1, contextValue)
                stmt.execute()
            }

            val result = block(JdbcTransactionalSession(connection, principal))
            try {
                connection.commit()
            } catch (commitError: Throwable) {
                evict = true
                throw commitError
            }
            result
        } catch (error: Throwable) {
            try {
                if (!connection.isClosed) {
                    connection.rollback()
                }
            } catch (rollbackError: Throwable) {
                evict = true
                error.addSuppressed(rollbackError)
            }
            throw error
        } finally {
            try {
                if (!evict && !connection.isClosed && connection.autoCommit) {
                    connection.autoCommit = false
                }
            } catch (_: Throwable) {
                evict = true
            }

            if (evict) {
                runCatching { pool.evictConnection(connection) }
            }
            runCatching { connection.close() }
        }
    }

    private companion object {
        const val SET_TX_READ_ONLY_SQL = "SET TRANSACTION READ ONLY"
        const val SET_TX_READ_WRITE_SQL = "SET TRANSACTION READ WRITE"
        const val SET_LOCAL_USER_ID_SQL = "SELECT set_config('app.user_id', ?, true)"
    }
}
