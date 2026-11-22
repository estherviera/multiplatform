package com.nexora.backend.adapter.persistence.postgres

import java.net.URI

data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val schema: String = DEFAULT_SCHEMA,
    val sslMode: String = DEFAULT_LOCAL_SSL_MODE,
    val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE,
    val minIdle: Int = minOf(DEFAULT_MIN_IDLE, maxPoolSize),
    val connectionTimeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT_MS,
    val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    val maxLifetimeMs: Long = DEFAULT_MAX_LIFETIME_MS,
    val statementTimeoutMs: Int = DEFAULT_STATEMENT_TIMEOUT_MS,
    val lockTimeoutMs: Int = DEFAULT_LOCK_TIMEOUT_MS,
    val idleInTransactionTimeoutMs: Int = DEFAULT_IDLE_TX_TIMEOUT_MS,
    val prepareThreshold: Int? = null,
    val ioMaxParallelism: Int = maxPoolSize,
) {
    init {
        validatePostgresJdbcUrl(jdbcUrl, "NEXORA_RUNTIME_JDBC_URL")
        require(username.isNotBlank()) { "NEXORA_RUNTIME_USER must not be blank" }
        require(username.lowercase() !in DISALLOWED_RUNTIME_USERS) {
            "NEXORA_RUNTIME_USER '$username' is not allowed for runtime persistence; use a least-privilege runtime role such as app_runtime"
        }
        require(password.isNotEmpty()) { "NEXORA_RUNTIME_PASSWORD must not be empty" }
        require(SCHEMA_REGEX.matches(schema)) { "Invalid NEXORA_RUNTIME_SCHEMA '$schema'" }
        require(sslMode in VALID_SSL_MODES) {
            "Invalid NEXORA_RUNTIME_SSL_MODE '$sslMode'. Allowed: ${VALID_SSL_MODES.joinToString(", ")}"
        }
        require(maxPoolSize in 1..50) { "NEXORA_DB_MAX_POOL_SIZE must be between 1 and 50, got $maxPoolSize" }
        require(minIdle in 0..maxPoolSize) { "NEXORA_DB_MIN_IDLE must be between 0 and $maxPoolSize, got $minIdle" }
        require(connectionTimeoutMs in 500..30_000) {
            "NEXORA_DB_CONNECTION_TIMEOUT_MS must be between 500 and 30000"
        }
        require(idleTimeoutMs in 10_000..1_800_000) {
            "NEXORA_DB_IDLE_TIMEOUT_MS must be between 10000 and 1800000"
        }
        require(maxLifetimeMs in 30_000..3_600_000) {
            "NEXORA_DB_MAX_LIFETIME_MS must be between 30000 and 3600000"
        }
        require(statementTimeoutMs in 500..120_000) {
            "NEXORA_DB_STATEMENT_TIMEOUT_MS must be between 500 and 120000"
        }
        require(lockTimeoutMs in 100..30_000) {
            "NEXORA_DB_LOCK_TIMEOUT_MS must be between 100 and 30000"
        }
        require(idleInTransactionTimeoutMs in 1_000..120_000) {
            "NEXORA_DB_IDLE_TX_TIMEOUT_MS must be between 1000 and 120000"
        }
        if (prepareThreshold != null) {
            require(prepareThreshold in 0..100) { "NEXORA_DB_PREPARE_THRESHOLD must be between 0 and 100" }
        }
        require(ioMaxParallelism in 1..64) { "NEXORA_DB_IO_PARALLELISM must be between 1 and 64" }
    }

    override fun toString(): String =
        "DatabaseConfig(jdbcUrl=${redactJdbcUrl(jdbcUrl)}, username=$username, schema=$schema, " +
            "sslMode=$sslMode, maxPoolSize=$maxPoolSize, minIdle=$minIdle, " +
            "connectionTimeoutMs=$connectionTimeoutMs, statementTimeoutMs=$statementTimeoutMs)"

    companion object {
        const val DEFAULT_SCHEMA = "nexora"
        const val DEFAULT_LOCAL_SSL_MODE = "prefer"
        const val DEFAULT_RESTRICTED_SSL_MODE = "verify-full"
        const val DEFAULT_MAX_POOL_SIZE = 10
        const val DEFAULT_MIN_IDLE = 2
        const val DEFAULT_CONNECTION_TIMEOUT_MS = 5_000L
        const val DEFAULT_IDLE_TIMEOUT_MS = 300_000L
        const val DEFAULT_MAX_LIFETIME_MS = 1_800_000L
        const val DEFAULT_STATEMENT_TIMEOUT_MS = 10_000
        const val DEFAULT_LOCK_TIMEOUT_MS = 3_000
        const val DEFAULT_IDLE_TX_TIMEOUT_MS = 15_000

        val SCHEMA_REGEX = Regex("^[a-z_][a-z0-9_]{0,62}$")
        val VALID_SSL_MODES = setOf("disable", "allow", "prefer", "require", "verify-ca", "verify-full")
        val STRICT_SSL_MODES = setOf("verify-ca", "verify-full")

        private val DISALLOWED_RUNTIME_USERS = setOf(
            "postgres",
            "supabase_admin",
            "supabase_auth_admin",
            "service_role",
            "app_migrator",
        )

        fun validatePostgresJdbcUrl(url: String, envName: String) {
            require(url.startsWith("jdbc:postgresql://")) {
                "$envName must start with 'jdbc:postgresql://'"
            }
            val stripped = url.removePrefix("jdbc:")
            val uri = try {
                URI(stripped)
            } catch (_: Exception) {
                throw IllegalArgumentException("$envName is not a valid PostgreSQL JDBC URL")
            }
            require(!uri.host.isNullOrBlank()) { "$envName must specify a valid host" }
            require(uri.rawUserInfo == null) { "$envName must not embed credentials in URL userInfo" }
            val query = uri.rawQuery ?: ""
            require(!query.contains("password=", ignoreCase = true)) {
                "$envName must not embed a password query parameter"
            }
            require(!uri.path.isNullOrBlank() && uri.path != "/") {
                "$envName must specify a database name"
            }
        }

        fun redactJdbcUrl(url: String): String =
            url.substringBefore("?")
    }
}
