package com.nexora.backend.bootstrap

import com.nexora.backend.adapter.auth.GlobalAdminAuthConfig
import com.nexora.backend.adapter.auth.JwtAuthConfig
import com.nexora.backend.adapter.http.ktor.ClientIpResolver
import com.nexora.backend.adapter.persistence.postgres.DatabaseConfig
import com.nexora.backend.adapter.persistence.postgres.PlatformDatabaseConfig
import com.nexora.backend.application.auth.GlobalAdminPolicyConfig
import com.nexora.backend.application.platform.PlatformRateLimiter
import java.net.URI
import java.util.UUID

enum class AppEnvironment(val wireName: String) {
    LOCAL("local"),
    TEST("test"),
    STAGING("staging"),
    PRODUCTION("production");

    val isRestricted: Boolean
        get() = this == STAGING || this == PRODUCTION

    companion object {
        fun parse(raw: String): AppEnvironment {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.wireName == normalized }
                ?: throw IllegalArgumentException(
                    "Invalid NEXORA_ENV '$raw'. Allowed values: ${entries.joinToString(", ") { it.wireName }}",
                )
        }
    }
}

data class AppConfig(
    val environment: AppEnvironment,
    val host: String,
    val port: Int,
    val serviceName: String,
    val corsAllowedOrigins: List<String>,
    val readinessToken: String?,
    val shutdownGracePeriodMs: Long,
    val shutdownTimeoutMs: Long,
    val auth: JwtAuthConfig? = null,
    val database: DatabaseConfig? = null,
    val globalAdmin: GlobalAdminAuthConfig? = null,
    val platformDatabase: PlatformDatabaseConfig? = null,
    val platformRateLimitMaxRequests: Int = PlatformRateLimiter.DEFAULT_MAX_REQUESTS_PER_CALLER,
    val platformRateLimitWindowSeconds: Long = PlatformRateLimiter.DEFAULT_WINDOW.seconds,
    val trustedProxies: Set<String> = emptySet(),
) {
    init {
        require(host.isNotBlank()) { "NEXORA_HOST must not be blank" }
        require(port in 0..65535) { "NEXORA_PORT must be between 0 and 65535, got $port" }
        require(serviceName.isNotBlank()) { "NEXORA_SERVICE_NAME must not be blank" }
        require(shutdownGracePeriodMs >= 0) { "NEXORA_SHUTDOWN_GRACE_MS must be >= 0" }
        require(shutdownTimeoutMs >= shutdownGracePeriodMs) {
            "NEXORA_SHUTDOWN_TIMEOUT_MS must be >= NEXORA_SHUTDOWN_GRACE_MS"
        }
        require(platformRateLimitMaxRequests in 1..1_000) {
            "NEXORA_PLATFORM_RATE_LIMIT_MAX_REQUESTS must be between 1 and 1000"
        }
        require(platformRateLimitWindowSeconds in 1..3_600) {
            "NEXORA_PLATFORM_RATE_LIMIT_WINDOW_SECONDS must be between 1 and 3600"
        }
        trustedProxies.forEach { ClientIpResolver.parseAndValidateProxyIp(it) }
        corsAllowedOrigins.forEach { validateOrigin(it) }
        if (environment.isRestricted) {
            require(!readinessToken.isNullOrBlank()) {
                "NEXORA_READINESS_TOKEN is required in ${environment.wireName} environment"
            }
            require(auth != null) {
                "NEXORA_AUTH_ISSUER and NEXORA_AUTH_JWKS_URL are required in ${environment.wireName} environment"
            }
        }
        if (auth != null) {
            validateAuthTransport(auth, environment)
        }
        if (globalAdmin != null) {
            require(auth != null) {
                "NEXORA_AUTH_ISSUER and NEXORA_AUTH_JWKS_URL are required when NEXORA_GLOBAL_ADMIN_SUB is configured"
            }
            require(globalAdmin.expectedIssuer == auth.issuer) {
                "globalAdmin.expectedIssuer must match NEXORA_AUTH_ISSUER"
            }
            validateEndpointUri(globalAdmin.authUserUri, "NEXORA_GLOBAL_ADMIN_AUTH_USER_URL", environment)
        }
        if (database != null && environment.isRestricted) {
            require(database.sslMode in DatabaseConfig.STRICT_SSL_MODES) {
                "NEXORA_RUNTIME_SSL_MODE must be verify-ca or verify-full in ${environment.wireName} environment"
            }
        }
        if (platformDatabase != null && environment.isRestricted) {
            require(platformDatabase.sslMode in DatabaseConfig.STRICT_SSL_MODES) {
                "NEXORA_PLATFORM_SSL_MODE must be verify-ca or verify-full in ${environment.wireName} environment"
            }
        }
    }

    override fun toString(): String =
        "AppConfig(environment=${environment.wireName}, host=$host, port=$port, serviceName=$serviceName, " +
            "corsAllowedOrigins=$corsAllowedOrigins, readinessTokenConfigured=${readinessToken != null}, " +
            "authConfigured=${auth != null}, databaseConfigured=${database != null}, " +
            "globalAdminConfigured=${globalAdmin != null}, platformDatabaseConfigured=${platformDatabase != null}, " +
            "shutdownGracePeriodMs=$shutdownGracePeriodMs, shutdownTimeoutMs=$shutdownTimeoutMs)"

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8080
        const val DEFAULT_SERVICE_NAME = "nexora-saas-api"
        const val DEFAULT_SHUTDOWN_GRACE_MS = 1_000L
        const val DEFAULT_SHUTDOWN_TIMEOUT_MS = 5_000L
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val environment = AppEnvironment.parse(env["NEXORA_ENV"] ?: AppEnvironment.LOCAL.wireName)
            val host = (env["NEXORA_HOST"] ?: DEFAULT_HOST).trim()
            val port = parseInt(env["NEXORA_PORT"], DEFAULT_PORT, "NEXORA_PORT")
            val serviceName = (env["NEXORA_SERVICE_NAME"] ?: DEFAULT_SERVICE_NAME).trim()
            val corsOrigins = parseOrigins(env["NEXORA_CORS_ALLOWED_ORIGINS"])
            val readinessToken = env["NEXORA_READINESS_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() }
            val graceMs = parseLong(env["NEXORA_SHUTDOWN_GRACE_MS"], DEFAULT_SHUTDOWN_GRACE_MS, "NEXORA_SHUTDOWN_GRACE_MS")
            val timeoutMs = parseLong(env["NEXORA_SHUTDOWN_TIMEOUT_MS"], DEFAULT_SHUTDOWN_TIMEOUT_MS, "NEXORA_SHUTDOWN_TIMEOUT_MS")
            val auth = parseAuthConfig(env, environment)
            val database = parseDatabaseConfig(env, environment)
            val globalAdmin = parseGlobalAdminConfig(env, auth, environment)
            val platformDatabase = parsePlatformDatabaseConfig(env, environment)
            val platformRateLimitMaxRequests = parseInt(
                env["NEXORA_PLATFORM_RATE_LIMIT_MAX_REQUESTS"],
                PlatformRateLimiter.DEFAULT_MAX_REQUESTS_PER_CALLER,
                "NEXORA_PLATFORM_RATE_LIMIT_MAX_REQUESTS",
            )
            val platformRateLimitWindowSeconds = parseLong(
                env["NEXORA_PLATFORM_RATE_LIMIT_WINDOW_SECONDS"],
                PlatformRateLimiter.DEFAULT_WINDOW.seconds,
                "NEXORA_PLATFORM_RATE_LIMIT_WINDOW_SECONDS",
            )
            val trustedProxies = parseTrustedProxies(env["NEXORA_TRUSTED_PROXIES"])

            return AppConfig(
                environment = environment,
                host = host,
                port = port,
                serviceName = serviceName,
                corsAllowedOrigins = corsOrigins,
                readinessToken = readinessToken,
                shutdownGracePeriodMs = graceMs,
                shutdownTimeoutMs = timeoutMs,
                auth = auth,
                database = database,
                globalAdmin = globalAdmin,
                platformDatabase = platformDatabase,
                platformRateLimitMaxRequests = platformRateLimitMaxRequests,
                platformRateLimitWindowSeconds = platformRateLimitWindowSeconds,
                trustedProxies = trustedProxies,
            )
        }

        private fun parseTrustedProxies(raw: String?): Set<String> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { ClientIpResolver.parseAndValidateProxyIp(it) }
                .toSet()
        }

        private fun parsePlatformDatabaseConfig(
            env: Map<String, String>,
            environment: AppEnvironment,
        ): PlatformDatabaseConfig? {
            val jdbcUrl = env["NEXORA_PLATFORM_JDBC_URL"]?.trim()?.takeIf { it.isNotEmpty() }
            val username = env["NEXORA_PLATFORM_USER"]?.trim()?.takeIf { it.isNotEmpty() }
            val password = env["NEXORA_PLATFORM_PASSWORD"]?.takeIf { it.isNotEmpty() }

            if (jdbcUrl == null && username == null && password == null) {
                return null
            }

            require(jdbcUrl != null) { "NEXORA_PLATFORM_JDBC_URL is required when platform executor is configured" }
            require(username != null) { "NEXORA_PLATFORM_USER is required when platform executor is configured" }
            require(password != null) { "NEXORA_PLATFORM_PASSWORD is required when platform executor is configured" }

            val schema = env["NEXORA_PLATFORM_SCHEMA"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: PlatformDatabaseConfig.DEFAULT_SCHEMA
            val defaultSsl = if (environment.isRestricted) {
                DatabaseConfig.DEFAULT_RESTRICTED_SSL_MODE
            } else {
                DatabaseConfig.DEFAULT_LOCAL_SSL_MODE
            }
            val sslMode = env["NEXORA_PLATFORM_SSL_MODE"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: defaultSsl
            val maxPoolSize = parseInt(
                env["NEXORA_PLATFORM_DB_MAX_POOL_SIZE"],
                PlatformDatabaseConfig.DEFAULT_MAX_POOL_SIZE,
                "NEXORA_PLATFORM_DB_MAX_POOL_SIZE",
            )

            return PlatformDatabaseConfig(
                jdbcUrl = jdbcUrl,
                username = username,
                password = password,
                schema = schema,
                sslMode = sslMode,
                maxPoolSize = maxPoolSize,
            )
        }

        private fun parseGlobalAdminConfig(
            env: Map<String, String>,
            auth: JwtAuthConfig?,
            environment: AppEnvironment,
        ): GlobalAdminAuthConfig? {
            val rawSub = env["NEXORA_GLOBAL_ADMIN_SUB"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null

            val adminUserId = try {
                UUID.fromString(rawSub).takeIf { it.toString().equals(rawSub, ignoreCase = true) }
            } catch (_: IllegalArgumentException) {
                null
            } ?: throw IllegalArgumentException("NEXORA_GLOBAL_ADMIN_SUB must be a single valid canonical UUID")

            require(auth != null) {
                "NEXORA_AUTH_ISSUER and NEXORA_AUTH_JWKS_URL must be configured when NEXORA_GLOBAL_ADMIN_SUB is set"
            }

            val mfaMaxAgeSeconds = parseLong(
                env["NEXORA_GLOBAL_ADMIN_MFA_MAX_AGE_SECONDS"],
                GlobalAdminPolicyConfig.DEFAULT_MFA_MAX_AGE.seconds,
                "NEXORA_GLOBAL_ADMIN_MFA_MAX_AGE_SECONDS",
            )
            val authTimeoutMs = parseLong(
                env["NEXORA_GLOBAL_ADMIN_AUTH_TIMEOUT_MS"],
                GlobalAdminAuthConfig.DEFAULT_AUTH_TIMEOUT_MS,
                "NEXORA_GLOBAL_ADMIN_AUTH_TIMEOUT_MS",
            )
            val authUserUrlRaw = env["NEXORA_GLOBAL_ADMIN_AUTH_USER_URL"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${auth.issuer.trimEnd('/')}/user"
            val authUserUri = try {
                URI(authUserUrlRaw)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid NEXORA_GLOBAL_ADMIN_AUTH_USER_URL '$authUserUrlRaw'")
            }
            validateEndpointUri(authUserUri, "NEXORA_GLOBAL_ADMIN_AUTH_USER_URL", environment)

            val apiKey = env["NEXORA_AUTH_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }

            return GlobalAdminAuthConfig(
                globalAdminUserId = adminUserId,
                expectedIssuer = auth.issuer,
                authUserUri = authUserUri,
                mfaMaxAgeSeconds = mfaMaxAgeSeconds,
                authTimeoutMs = authTimeoutMs,
                clockSkewSeconds = auth.clockSkewSeconds,
                apiKey = apiKey,
            )
        }

        private fun parseDatabaseConfig(env: Map<String, String>, environment: AppEnvironment): DatabaseConfig? {
            val enabledRaw = env["NEXORA_DB_ENABLED"]?.trim()?.takeIf { it.isNotEmpty() }
            val explicitEnabled = enabledRaw?.let { parseBoolean(it, false, "NEXORA_DB_ENABLED") }
            if (explicitEnabled == false) return null

            val jdbcUrl = env["NEXORA_RUNTIME_JDBC_URL"]?.trim()?.takeIf { it.isNotEmpty() }
            val username = env["NEXORA_RUNTIME_USER"]?.trim()?.takeIf { it.isNotEmpty() }
            val password = env["NEXORA_RUNTIME_PASSWORD"]?.takeIf { it.isNotEmpty() }

            val anyRuntimeVarPresent = jdbcUrl != null || username != null || password != null
            if (explicitEnabled == null && !anyRuntimeVarPresent) {
                return null
            }

            require(jdbcUrl != null) { "NEXORA_RUNTIME_JDBC_URL is required when database persistence is enabled" }
            require(username != null) { "NEXORA_RUNTIME_USER is required when database persistence is enabled" }
            require(password != null) { "NEXORA_RUNTIME_PASSWORD is required when database persistence is enabled" }

            val schema = env["NEXORA_RUNTIME_SCHEMA"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: DatabaseConfig.DEFAULT_SCHEMA
            val defaultSsl = if (environment.isRestricted) {
                DatabaseConfig.DEFAULT_RESTRICTED_SSL_MODE
            } else {
                DatabaseConfig.DEFAULT_LOCAL_SSL_MODE
            }
            val sslMode = env["NEXORA_RUNTIME_SSL_MODE"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: defaultSsl
            val maxPoolSize = parseInt(env["NEXORA_DB_MAX_POOL_SIZE"], DatabaseConfig.DEFAULT_MAX_POOL_SIZE, "NEXORA_DB_MAX_POOL_SIZE")
            val minIdle = parseInt(
                env["NEXORA_DB_MIN_IDLE"],
                minOf(DatabaseConfig.DEFAULT_MIN_IDLE, maxPoolSize),
                "NEXORA_DB_MIN_IDLE",
            )
            val connectionTimeoutMs = parseLong(
                env["NEXORA_DB_CONNECTION_TIMEOUT_MS"],
                DatabaseConfig.DEFAULT_CONNECTION_TIMEOUT_MS,
                "NEXORA_DB_CONNECTION_TIMEOUT_MS",
            )
            val idleTimeoutMs = parseLong(
                env["NEXORA_DB_IDLE_TIMEOUT_MS"],
                DatabaseConfig.DEFAULT_IDLE_TIMEOUT_MS,
                "NEXORA_DB_IDLE_TIMEOUT_MS",
            )
            val maxLifetimeMs = parseLong(
                env["NEXORA_DB_MAX_LIFETIME_MS"],
                DatabaseConfig.DEFAULT_MAX_LIFETIME_MS,
                "NEXORA_DB_MAX_LIFETIME_MS",
            )
            val statementTimeoutMs = parseInt(
                env["NEXORA_DB_STATEMENT_TIMEOUT_MS"],
                DatabaseConfig.DEFAULT_STATEMENT_TIMEOUT_MS,
                "NEXORA_DB_STATEMENT_TIMEOUT_MS",
            )
            val lockTimeoutMs = parseInt(
                env["NEXORA_DB_LOCK_TIMEOUT_MS"],
                DatabaseConfig.DEFAULT_LOCK_TIMEOUT_MS,
                "NEXORA_DB_LOCK_TIMEOUT_MS",
            )
            val idleTxTimeoutMs = parseInt(
                env["NEXORA_DB_IDLE_TX_TIMEOUT_MS"],
                DatabaseConfig.DEFAULT_IDLE_TX_TIMEOUT_MS,
                "NEXORA_DB_IDLE_TX_TIMEOUT_MS",
            )
            val prepareThresholdRaw = env["NEXORA_DB_PREPARE_THRESHOLD"]?.trim()?.takeIf { it.isNotEmpty() }
            val prepareThreshold = prepareThresholdRaw?.let {
                it.toIntOrNull()
                    ?: throw IllegalArgumentException("NEXORA_DB_PREPARE_THRESHOLD must be a valid integer, got '$it'")
            }
            val ioParallelism = parseInt(env["NEXORA_DB_IO_PARALLELISM"], maxPoolSize, "NEXORA_DB_IO_PARALLELISM")

            return DatabaseConfig(
                jdbcUrl = jdbcUrl,
                username = username,
                password = password,
                schema = schema,
                sslMode = sslMode,
                maxPoolSize = maxPoolSize,
                minIdle = minIdle,
                connectionTimeoutMs = connectionTimeoutMs,
                idleTimeoutMs = idleTimeoutMs,
                maxLifetimeMs = maxLifetimeMs,
                statementTimeoutMs = statementTimeoutMs,
                lockTimeoutMs = lockTimeoutMs,
                idleInTransactionTimeoutMs = idleTxTimeoutMs,
                prepareThreshold = prepareThreshold,
                ioMaxParallelism = ioParallelism,
            )
        }

        private fun parseAuthConfig(env: Map<String, String>, environment: AppEnvironment): JwtAuthConfig? {
            val issuer = env["NEXORA_AUTH_ISSUER"]?.trim()?.takeIf { it.isNotEmpty() }
            val jwksUrlRaw = env["NEXORA_AUTH_JWKS_URL"]?.trim()?.takeIf { it.isNotEmpty() }

            if (issuer == null && jwksUrlRaw == null) {
                require(!environment.isRestricted) {
                    "NEXORA_AUTH_ISSUER and NEXORA_AUTH_JWKS_URL are required in ${environment.wireName} environment"
                }
                return null
            }

            require(issuer != null && jwksUrlRaw != null) {
                "Both NEXORA_AUTH_ISSUER and NEXORA_AUTH_JWKS_URL must be configured together"
            }

            val jwksUri = try {
                URI(jwksUrlRaw)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid NEXORA_AUTH_JWKS_URL '$jwksUrlRaw'")
            }

            val audience = env["NEXORA_AUTH_AUDIENCE"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: JwtAuthConfig.DEFAULT_AUDIENCE
            val allowedAlgorithms = parseAllowedAlgorithms(env["NEXORA_AUTH_ALLOWED_ALGS"])
            val allowAnonymous = parseBoolean(env["NEXORA_AUTH_ALLOW_ANONYMOUS"], false, "NEXORA_AUTH_ALLOW_ANONYMOUS")
            val clockSkewSeconds = parseLong(
                env["NEXORA_AUTH_CLOCK_SKEW_SECONDS"],
                JwtAuthConfig.DEFAULT_CLOCK_SKEW_SECONDS,
                "NEXORA_AUTH_CLOCK_SKEW_SECONDS",
            )
            val cacheTtlSeconds = parseLong(
                env["NEXORA_AUTH_JWKS_CACHE_TTL_SECONDS"],
                JwtAuthConfig.DEFAULT_JWKS_CACHE_TTL_SECONDS,
                "NEXORA_AUTH_JWKS_CACHE_TTL_SECONDS",
            )
            val minRefreshSeconds = parseLong(
                env["NEXORA_AUTH_JWKS_MIN_REFRESH_SECONDS"],
                JwtAuthConfig.DEFAULT_JWKS_MIN_REFRESH_SECONDS,
                "NEXORA_AUTH_JWKS_MIN_REFRESH_SECONDS",
            )
            val timeoutMs = parseLong(
                env["NEXORA_AUTH_JWKS_TIMEOUT_MS"],
                JwtAuthConfig.DEFAULT_JWKS_TIMEOUT_MS,
                "NEXORA_AUTH_JWKS_TIMEOUT_MS",
            )

            return JwtAuthConfig(
                issuer = issuer,
                audience = audience,
                jwksUri = jwksUri,
                allowedAlgorithms = allowedAlgorithms,
                allowAnonymous = allowAnonymous,
                clockSkewSeconds = clockSkewSeconds,
                jwksCacheTtlSeconds = cacheTtlSeconds,
                jwksMinRefreshSeconds = minRefreshSeconds,
                jwksTimeoutMs = timeoutMs,
            )
        }

        private fun parseAllowedAlgorithms(raw: String?): Set<String> {
            if (raw.isNullOrBlank()) return JwtAuthConfig.DEFAULT_ALLOWED_ALGORITHMS
            return raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        private fun parseBoolean(raw: String?, defaultValue: Boolean, name: String): Boolean {
            if (raw.isNullOrBlank()) return defaultValue
            return when (raw.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("$name must be 'true' or 'false', got '$raw'")
            }
        }

        private fun validateAuthTransport(auth: JwtAuthConfig, environment: AppEnvironment) {
            validateEndpointUri(auth.jwksUri, "NEXORA_AUTH_JWKS_URL", environment)
            val issuerUri = try {
                URI(auth.issuer)
            } catch (_: Exception) {
                throw IllegalArgumentException("NEXORA_AUTH_ISSUER must be a valid HTTP(S) URI")
            }
            validateEndpointUri(issuerUri, "NEXORA_AUTH_ISSUER", environment)
        }

        private fun validateEndpointUri(uri: URI, name: String, environment: AppEnvironment) {
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            require(scheme == "https" || scheme == "http") {
                "$name must use https (or loopback http in local/test)"
            }
            require(!host.isNullOrBlank()) { "$name must include a valid host" }
            require(uri.rawUserInfo == null) { "$name must not embed credentials" }
            if (scheme == "http") {
                require(!environment.isRestricted && host in LOOPBACK_HOSTS) {
                    "$name must use https outside local/test loopback"
                }
            }
        }

        private fun parseInt(raw: String?, defaultValue: Int, name: String): Int {
            if (raw.isNullOrBlank()) return defaultValue
            return raw.trim().toIntOrNull()
                ?: throw IllegalArgumentException("$name must be a valid integer, got '$raw'")
        }

        private fun parseLong(raw: String?, defaultValue: Long, name: String): Long {
            if (raw.isNullOrBlank()) return defaultValue
            return raw.trim().toLongOrNull()
                ?: throw IllegalArgumentException("$name must be a valid long integer, got '$raw'")
        }

        private fun parseOrigins(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .onEach { validateOrigin(it) }
                .distinct()
        }

        private fun validateOrigin(origin: String) {
            require(origin != "*") { "Wildcard '*' is not allowed in NEXORA_CORS_ALLOWED_ORIGINS" }
            val uri = try {
                URI(origin)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid CORS origin '$origin'")
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") {
                "CORS origin '$origin' must use http or https scheme"
            }
            require(!uri.host.isNullOrBlank()) {
                "CORS origin '$origin' must include a valid host"
            }
            require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
                "CORS origin '$origin' must not contain path, query, or fragment"
            }
        }
    }
}
