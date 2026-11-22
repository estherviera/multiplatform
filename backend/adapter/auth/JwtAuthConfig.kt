package com.nexora.backend.adapter.auth

import java.net.URI

data class JwtAuthConfig(
    val issuer: String,
    val audience: String = DEFAULT_AUDIENCE,
    val jwksUri: URI,
    val allowedAlgorithms: Set<String> = DEFAULT_ALLOWED_ALGORITHMS,
    val expectedRole: String = DEFAULT_EXPECTED_ROLE,
    val allowAnonymous: Boolean = false,
    val clockSkewSeconds: Long = DEFAULT_CLOCK_SKEW_SECONDS,
    val jwksCacheTtlSeconds: Long = DEFAULT_JWKS_CACHE_TTL_SECONDS,
    val jwksMinRefreshSeconds: Long = DEFAULT_JWKS_MIN_REFRESH_SECONDS,
    val jwksTimeoutMs: Long = DEFAULT_JWKS_TIMEOUT_MS,
    val maxCachedKeys: Int = DEFAULT_MAX_CACHED_KEYS,
) {
    init {
        require(issuer.isNotBlank()) { "NEXORA_AUTH_ISSUER must not be blank" }
        require(audience.isNotBlank()) { "NEXORA_AUTH_AUDIENCE must not be blank" }
        require(expectedRole.isNotBlank()) { "expectedRole must not be blank" }
        require(expectedRole != "service_role" && expectedRole != "anon") {
            "expectedRole must not be a privileged service or anonymous role"
        }
        require(allowedAlgorithms.isNotEmpty()) { "NEXORA_AUTH_ALLOWED_ALGS must not be empty" }
        val unsupported = allowedAlgorithms - SUPPORTED_ASYMMETRIC_ALGORITHMS
        require(unsupported.isEmpty()) {
            "Unsupported or disallowed JWT algorithm(s): ${unsupported.joinToString(", ")}. " +
                "Allowed asymmetric algorithms: ${SUPPORTED_ASYMMETRIC_ALGORITHMS.joinToString(", ")}"
        }
        require(clockSkewSeconds in 0..MAX_CLOCK_SKEW_SECONDS) {
            "NEXORA_AUTH_CLOCK_SKEW_SECONDS must be between 0 and $MAX_CLOCK_SKEW_SECONDS"
        }
        require(jwksCacheTtlSeconds in 5..3600) {
            "NEXORA_AUTH_JWKS_CACHE_TTL_SECONDS must be between 5 and 3600"
        }
        require(jwksMinRefreshSeconds in 0..300) {
            "NEXORA_AUTH_JWKS_MIN_REFRESH_SECONDS must be between 0 and 300"
        }
        require(jwksTimeoutMs in 500..30_000) {
            "NEXORA_AUTH_JWKS_TIMEOUT_MS must be between 500 and 30000"
        }
        require(maxCachedKeys in 1..128) {
            "maxCachedKeys must be between 1 and 128"
        }
    }

    companion object {
        const val DEFAULT_AUDIENCE = "authenticated"
        const val DEFAULT_EXPECTED_ROLE = "authenticated"
        const val DEFAULT_CLOCK_SKEW_SECONDS = 30L
        const val MAX_CLOCK_SKEW_SECONDS = 120L
        const val DEFAULT_JWKS_CACHE_TTL_SECONDS = 600L
        const val DEFAULT_JWKS_MIN_REFRESH_SECONDS = 30L
        const val DEFAULT_JWKS_TIMEOUT_MS = 5_000L
        const val DEFAULT_MAX_CACHED_KEYS = 32

        val SUPPORTED_ASYMMETRIC_ALGORITHMS: Set<String> = setOf(
            "ES256",
            "ES384",
            "ES512",
            "RS256",
            "RS384",
            "RS512",
            "PS256",
        )

        val DEFAULT_ALLOWED_ALGORITHMS: Set<String> = setOf("ES256")
    }
}
