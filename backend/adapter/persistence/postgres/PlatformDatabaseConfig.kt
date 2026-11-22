package com.nexora.backend.adapter.persistence.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Separate, least-privilege JDBC configuration for the dedicated platform SQL executor (`app_platform`).
 *
 * Security invariants:
 * - Strictly rejects `app_runtime`, `app_migrator`, `postgres`, `supabase_admin`, `supabase_auth_admin`,
 *   and `service_role` so Ktor never shares runtime or migration credentials with platform operations.
 * - Uses a tiny isolated HikariCP pool (`maxPoolSize` 1..5, default 2) targeting schema `nexora_platform`.
 */
data class PlatformDatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val schema: String = DEFAULT_SCHEMA,
    val sslMode: String = DatabaseConfig.DEFAULT_LOCAL_SSL_MODE,
    val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE,
    val minIdle: Int = minOf(DEFAULT_MIN_IDLE, maxPoolSize),
    val connectionTimeoutMs: Long = DatabaseConfig.DEFAULT_CONNECTION_TIMEOUT_MS,
    val statementTimeoutMs: Int = DatabaseConfig.DEFAULT_STATEMENT_TIMEOUT_MS,
    val lockTimeoutMs: Int = DatabaseConfig.DEFAULT_LOCK_TIMEOUT_MS,
    val idleInTransactionTimeoutMs: Int = DatabaseConfig.DEFAULT_IDLE_TX_TIMEOUT_MS,
    val prepareThreshold: Int? = null,
) {
    init {
        DatabaseConfig.validatePostgresJdbcUrl(jdbcUrl, "NEXORA_PLATFORM_JDBC_URL")
        require(username.isNotBlank()) { "NEXORA_PLATFORM_USER must not be blank" }
        require(username.lowercase() !in DISALLOWED_PLATFORM_USERS) {
            "NEXORA_PLATFORM_USER '$username' is not allowed; use the dedicated platform executor role (e.g. app_platform)"
        }
        require(password.isNotEmpty()) { "NEXORA_PLATFORM_PASSWORD must not be empty" }
        require(DatabaseConfig.SCHEMA_REGEX.matches(schema)) { "Invalid NEXORA_PLATFORM_SCHEMA '$schema'" }
        require(schema != "auth" && schema != "pg_catalog" && schema != "information_schema") {
            "NEXORA_PLATFORM_SCHEMA '$schema' must not target internal schemas"
        }
        require(sslMode in DatabaseConfig.VALID_SSL_MODES) {
            "Invalid NEXORA_PLATFORM_SSL_MODE '$sslMode'"
        }
        require(maxPoolSize in 1..MAX_ALLOWED_POOL_SIZE) {
            "NEXORA_PLATFORM_DB_MAX_POOL_SIZE must be between 1 and $MAX_ALLOWED_POOL_SIZE"
        }
        require(minIdle in 0..maxPoolSize) { "minIdle must be between 0 and $maxPoolSize" }
        require(connectionTimeoutMs in 500..30_000) { "connectionTimeoutMs must be between 500 and 30000" }
        require(statementTimeoutMs in 500..120_000) { "statementTimeoutMs must be between 500 and 120000" }
        require(lockTimeoutMs in 100..30_000) { "lockTimeoutMs must be between 100 and 30000" }
        require(idleInTransactionTimeoutMs in 1_000..120_000) {
            "idleInTransactionTimeoutMs must be between 1000 and 120000"
        }
        if (prepareThreshold != null) {
            require(prepareThreshold in 0..100) { "prepareThreshold must be between 0 and 100" }
        }
    }

    override fun toString(): String =
        "PlatformDatabaseConfig(jdbcUrl=${DatabaseConfig.redactJdbcUrl(jdbcUrl)}, username=$username, " +
            "schema=$schema, sslMode=$sslMode, maxPoolSize=$maxPoolSize)"

    companion object {
        const val DEFAULT_SCHEMA = "nexora_platform"
        const val DEFAULT_MAX_POOL_SIZE = 2
        const val DEFAULT_MIN_IDLE = 1
        const val MAX_ALLOWED_POOL_SIZE = 5

        private val DISALLOWED_PLATFORM_USERS = setOf(
            "app_runtime",
            "app_migrator",
            "postgres",
            "supabase_admin",
            "supabase_auth_admin",
            "service_role",
        )
    }
}

class PostgresPlatformPool private constructor(
    val dataSource: HikariDataSource,
    val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable {

    val isClosed: Boolean
        get() = dataSource.isClosed

    fun evictConnection(connection: Connection) {
        dataSource.evictConnection(connection)
    }

    override fun close() {
        if (!dataSource.isClosed) {
            dataSource.close()
        }
    }

    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun create(
            config: PlatformDatabaseConfig,
            failFast: Boolean = true,
        ): PostgresPlatformPool {
            val hikariConfig = HikariConfig().apply {
                poolName = "nexora-platform-pool"
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                driverClassName = "org.postgresql.Driver"
                isAutoCommit = false
                isIsolateInternalQueries = true
                maximumPoolSize = config.maxPoolSize
                minimumIdle = config.minIdle
                connectionTimeout = config.connectionTimeoutMs
                initializationFailTimeout = if (failFast) config.connectionTimeoutMs else -1L
                addDataSourceProperty("sslmode", config.sslMode)
                addDataSourceProperty("currentSchema", config.schema)
                addDataSourceProperty("ApplicationName", "nexora-saas-platform")
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
            val dispatcher = Dispatchers.IO.limitedParallelism(config.maxPoolSize)
            return PostgresPlatformPool(dataSource, dispatcher)
        }
    }
}
