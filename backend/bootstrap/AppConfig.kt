package com.nexora.backend.bootstrap

import java.net.URI

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
) {
    init {
        require(host.isNotBlank()) { "NEXORA_HOST must not be blank" }
        require(port in 0..65535) { "NEXORA_PORT must be between 0 and 65535, got $port" }
        require(serviceName.isNotBlank()) { "NEXORA_SERVICE_NAME must not be blank" }
        require(shutdownGracePeriodMs >= 0) { "NEXORA_SHUTDOWN_GRACE_MS must be >= 0" }
        require(shutdownTimeoutMs >= shutdownGracePeriodMs) {
            "NEXORA_SHUTDOWN_TIMEOUT_MS must be >= NEXORA_SHUTDOWN_GRACE_MS"
        }
        corsAllowedOrigins.forEach { validateOrigin(it) }
        if (environment.isRestricted) {
            require(!readinessToken.isNullOrBlank()) {
                "NEXORA_READINESS_TOKEN is required in ${environment.wireName} environment"
            }
        }
    }

    override fun toString(): String =
        "AppConfig(environment=${environment.wireName}, host=$host, port=$port, serviceName=$serviceName, " +
            "corsAllowedOrigins=$corsAllowedOrigins, readinessTokenConfigured=${readinessToken != null}, " +
            "shutdownGracePeriodMs=$shutdownGracePeriodMs, shutdownTimeoutMs=$shutdownTimeoutMs)"

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8080
        const val DEFAULT_SERVICE_NAME = "nexora-saas-api"
        const val DEFAULT_SHUTDOWN_GRACE_MS = 1_000L
        const val DEFAULT_SHUTDOWN_TIMEOUT_MS = 5_000L

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val environment = AppEnvironment.parse(env["NEXORA_ENV"] ?: AppEnvironment.LOCAL.wireName)
            val host = (env["NEXORA_HOST"] ?: DEFAULT_HOST).trim()
            val port = parseInt(env["NEXORA_PORT"], DEFAULT_PORT, "NEXORA_PORT")
            val serviceName = (env["NEXORA_SERVICE_NAME"] ?: DEFAULT_SERVICE_NAME).trim()
            val corsOrigins = parseOrigins(env["NEXORA_CORS_ALLOWED_ORIGINS"])
            val readinessToken = env["NEXORA_READINESS_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() }
            val graceMs = parseLong(env["NEXORA_SHUTDOWN_GRACE_MS"], DEFAULT_SHUTDOWN_GRACE_MS, "NEXORA_SHUTDOWN_GRACE_MS")
            val timeoutMs = parseLong(env["NEXORA_SHUTDOWN_TIMEOUT_MS"], DEFAULT_SHUTDOWN_TIMEOUT_MS, "NEXORA_SHUTDOWN_TIMEOUT_MS")

            return AppConfig(
                environment = environment,
                host = host,
                port = port,
                serviceName = serviceName,
                corsAllowedOrigins = corsOrigins,
                readinessToken = readinessToken,
                shutdownGracePeriodMs = graceMs,
                shutdownTimeoutMs = timeoutMs,
            )
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
