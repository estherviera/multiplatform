package com.nexora.backend.adapter.persistence.postgres

import com.nexora.backend.application.health.HealthCheckResult
import com.nexora.backend.application.health.HealthStatus
import com.nexora.backend.application.health.ReadinessCheck
import com.nexora.backend.application.persistence.TransactionManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

class PostgresRuntimePool private constructor(
    val dataSource: HikariDataSource,
    val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable {

    val isClosed: Boolean
        get() = dataSource.isClosed

    fun evictConnection(connection: Connection) {
        dataSource.evictConnection(connection)
    }

    fun readinessCheck(transactionManager: TransactionManager): ReadinessCheck = ReadinessCheck {
        if (isClosed) {
            return@ReadinessCheck HealthCheckResult(HealthStatus.NOT_READY)
        }
        try {
            val ok = transactionManager.inInfrastructureTransaction(readOnly = true) { session ->
                session.connection.prepareStatement("SELECT 1").use { stmt ->
                    stmt.executeQuery().use { rs -> rs.next() && rs.getInt(1) == 1 }
                }
            }
            HealthCheckResult(if (ok) HealthStatus.OK else HealthStatus.NOT_READY)
        } catch (_: Exception) {
            HealthCheckResult(HealthStatus.NOT_READY)
        }
    }

    override fun close() {
        if (!dataSource.isClosed) {
            dataSource.close()
        }
    }

    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun create(
            config: DatabaseConfig,
            failFast: Boolean = true,
        ): PostgresRuntimePool {
            val hikariConfig = HikariConfig().apply {
                poolName = "nexora-runtime-pool"
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                driverClassName = "org.postgresql.Driver"
                isAutoCommit = false
                isIsolateInternalQueries = true
                maximumPoolSize = config.maxPoolSize
                minimumIdle = config.minIdle
                connectionTimeout = config.connectionTimeoutMs
                idleTimeout = config.idleTimeoutMs
                maxLifetime = config.maxLifetimeMs
                initializationFailTimeout = if (failFast) config.connectionTimeoutMs else -1L
                addDataSourceProperty("sslmode", config.sslMode)
                addDataSourceProperty("currentSchema", config.schema)
                addDataSourceProperty("ApplicationName", "nexora-saas-runtime")
                addDataSourceProperty(
                    "options",
                    "-c statement_timeout=${config.statementTimeoutMs} " +
                        "-c lock_timeout=${config.lockTimeoutMs} " +
                        "-c idle_in_transaction_session_timeout=${config.idleInTransactionTimeoutMs}",
                )
                if (config.prepareThreshold != null) {
                    addDataSourceProperty("prepareThreshold", config.prepareThreshold.toString())
                }
            }

            val dataSource = HikariDataSource(hikariConfig)
            val dispatcher = Dispatchers.IO.limitedParallelism(config.ioMaxParallelism)
            return PostgresRuntimePool(dataSource, dispatcher)
        }
    }
}
