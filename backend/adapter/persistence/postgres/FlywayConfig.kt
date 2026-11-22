package com.nexora.backend.adapter.persistence.postgres

data class FlywayConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val schema: String = DatabaseConfig.DEFAULT_SCHEMA,
    val locations: List<String> = DEFAULT_LOCATIONS,
    val sslMode: String = DatabaseConfig.DEFAULT_LOCAL_SSL_MODE,
    val connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
) {
    init {
        DatabaseConfig.validatePostgresJdbcUrl(jdbcUrl, "FLYWAY_URL")
        require(username.isNotBlank()) { "FLYWAY_USER must not be blank" }
        require(username.lowercase() !in DISALLOWED_MIGRATOR_USERS) {
            "FLYWAY_USER '$username' is not allowed for Flyway migrations; use the dedicated migrator role (e.g. app_migrator)"
        }
        require(password.isNotEmpty()) { "FLYWAY_PASSWORD must not be empty" }
        require(DatabaseConfig.SCHEMA_REGEX.matches(schema)) { "Invalid FLYWAY_SCHEMA '$schema'" }
        require(schema != "auth" && schema != "pg_catalog" && schema != "information_schema") {
            "FLYWAY_SCHEMA '$schema' must not target internal or Supabase-managed schemas"
        }
        require(locations.isNotEmpty() && locations.all { it.isNotBlank() }) {
            "FLYWAY_LOCATIONS must contain at least one valid location"
        }
        require(sslMode in DatabaseConfig.VALID_SSL_MODES) {
            "Invalid FLYWAY_SSL_MODE '$sslMode'"
        }
        require(connectTimeoutSeconds in 1..60) {
            "FLYWAY_CONNECT_TIMEOUT_SECONDS must be between 1 and 60"
        }
    }

    override fun toString(): String =
        "FlywayConfig(jdbcUrl=${DatabaseConfig.redactJdbcUrl(jdbcUrl)}, username=$username, " +
            "schema=$schema, locations=$locations, sslMode=$sslMode, connectTimeoutSeconds=$connectTimeoutSeconds)"

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10
        val DEFAULT_LOCATIONS: List<String> = listOf("classpath:db/migration")

        private val DISALLOWED_MIGRATOR_USERS = setOf(
            "app_runtime",
            "postgres",
            "supabase_admin",
            "supabase_auth_admin",
            "service_role",
        )

        fun fromEnv(env: Map<String, String> = System.getenv()): FlywayConfig {
            val jdbcUrl = env["FLYWAY_URL"]?.trim()
                ?: throw IllegalArgumentException("FLYWAY_URL is required")
            val username = env["FLYWAY_USER"]?.trim()
                ?: throw IllegalArgumentException("FLYWAY_USER is required")
            val password = env["FLYWAY_PASSWORD"]
                ?: throw IllegalArgumentException("FLYWAY_PASSWORD is required")
            val schema = env["FLYWAY_SCHEMA"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: DatabaseConfig.DEFAULT_SCHEMA
            val locations = env["FLYWAY_LOCATIONS"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_LOCATIONS
            val sslMode = env["FLYWAY_SSL_MODE"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: DatabaseConfig.DEFAULT_LOCAL_SSL_MODE
            val timeoutRaw = env["FLYWAY_CONNECT_TIMEOUT_SECONDS"]?.trim()?.takeIf { it.isNotEmpty() }
            val connectTimeout = if (timeoutRaw == null) {
                DEFAULT_CONNECT_TIMEOUT_SECONDS
            } else {
                timeoutRaw.toIntOrNull()
                    ?: throw IllegalArgumentException("FLYWAY_CONNECT_TIMEOUT_SECONDS must be an integer")
            }

            return FlywayConfig(
                jdbcUrl = jdbcUrl,
                username = username,
                password = password,
                schema = schema,
                locations = locations,
                sslMode = sslMode,
                connectTimeoutSeconds = connectTimeout,
            )
        }
    }
}
