package com.nexora.backend.adapter.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.application.auth.AuthSessionState
import com.nexora.backend.application.auth.AuthSessionVerifier
import com.nexora.backend.application.auth.GlobalAdminPolicyConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Typed configuration for the global admin verification adapter against Supabase Auth.
 *
 * Uses ONLY the caller's verified user Bearer access token (plus an optional public/anon gateway
 * `apiKey` when calling hosted Supabase behind Kong). Explicitly rejects `service_role` keys.
 */
data class GlobalAdminAuthConfig(
    val globalAdminUserId: UUID,
    val expectedIssuer: String,
    val authUserUri: URI,
    val mfaMaxAgeSeconds: Long = GlobalAdminPolicyConfig.DEFAULT_MFA_MAX_AGE.seconds,
    val authTimeoutMs: Long = DEFAULT_AUTH_TIMEOUT_MS,
    val clockSkewSeconds: Long = GlobalAdminPolicyConfig.DEFAULT_CLOCK_SKEW.seconds,
    val apiKey: String? = null,
) {
    init {
        require(expectedIssuer.isNotBlank()) { "expectedIssuer must not be blank" }
        require(
            mfaMaxAgeSeconds in GlobalAdminPolicyConfig.MIN_MFA_MAX_AGE.seconds..GlobalAdminPolicyConfig.MAX_MFA_MAX_AGE.seconds,
        ) {
            "NEXORA_GLOBAL_ADMIN_MFA_MAX_AGE_SECONDS must be between " +
                "${GlobalAdminPolicyConfig.MIN_MFA_MAX_AGE.seconds} and ${GlobalAdminPolicyConfig.MAX_MFA_MAX_AGE.seconds}"
        }
        require(authTimeoutMs in MIN_AUTH_TIMEOUT_MS..MAX_AUTH_TIMEOUT_MS) {
            "NEXORA_GLOBAL_ADMIN_AUTH_TIMEOUT_MS must be between $MIN_AUTH_TIMEOUT_MS and $MAX_AUTH_TIMEOUT_MS"
        }
        require(clockSkewSeconds in 0..GlobalAdminPolicyConfig.MAX_CLOCK_SKEW.seconds) {
            "clockSkewSeconds must be between 0 and ${GlobalAdminPolicyConfig.MAX_CLOCK_SKEW.seconds}"
        }
        if (apiKey != null) {
            require(apiKey.isNotBlank()) { "NEXORA_AUTH_API_KEY must not be blank when provided" }
            require(!isServiceRoleJwt(apiKey)) {
                "NEXORA_AUTH_API_KEY must not be a service_role key"
            }
        }
    }

    fun toPolicyConfig(): GlobalAdminPolicyConfig = GlobalAdminPolicyConfig(
        globalAdminUserId = globalAdminUserId,
        expectedIssuer = expectedIssuer,
        mfaMaxAge = Duration.ofSeconds(mfaMaxAgeSeconds),
        clockSkew = Duration.ofSeconds(clockSkewSeconds),
        requireVerifiedMfaFactorInSessionCheck = true,
    )

    override fun toString(): String =
        "GlobalAdminAuthConfig(configured=true, authUserUri=$authUserUri, " +
            "mfaMaxAgeSeconds=$mfaMaxAgeSeconds, authTimeoutMs=$authTimeoutMs, " +
            "apiKeyConfigured=${apiKey != null})"

    companion object {
        const val MIN_AUTH_TIMEOUT_MS = 500L
        const val DEFAULT_AUTH_TIMEOUT_MS = 3_000L
        const val MAX_AUTH_TIMEOUT_MS = 15_000L

        private val mapper = ObjectMapper()

        internal fun isServiceRoleJwt(rawKey: String): Boolean {
            val parts = rawKey.trim().split('.')
            if (parts.size != 3) return false
            return try {
                val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                val role = mapper.readTree(payloadJson).get("role")?.asText()?.trim()?.lowercase()
                role == "service_role" || role == "supabase_admin"
            } catch (_: Exception) {
                false
            }
        }
    }
}

/**
 * Verifies live session validity, account state, and enrolled MFA factors by calling
 * Supabase Auth's `GET /user` endpoint using the caller's own verified Bearer access token
 * (never `service_role`).
 *
 * Security controls:
 * - `Redirect.NEVER` so the Bearer token can never be forwarded to a redirect target.
 * - Explicit connect and request timeouts.
 * - Strict response size cap (`MAX_RESPONSE_BYTES`).
 * - Fail-closed (`AuthSessionState.Unavailable`) on any transport, timeout, 5xx, redirect, or parse error.
 */
class SupabaseAuthSessionVerifier(
    private val userEndpointUri: URI,
    private val timeout: Duration = Duration.ofMillis(GlobalAdminAuthConfig.DEFAULT_AUTH_TIMEOUT_MS),
    private val apiKey: String? = null,
    private val clock: Clock = Clock.systemUTC(),
    httpClient: HttpClient? = null,
) : AuthSessionVerifier {

    private val client: HttpClient = httpClient ?: HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private val mapper = ObjectMapper()

    init {
        if (apiKey != null) {
            require(!GlobalAdminAuthConfig.isServiceRoleJwt(apiKey)) {
                "SupabaseAuthSessionVerifier must never use a service_role key"
            }
        }
    }

    override fun verifyActiveSession(rawAccessToken: String): AuthSessionState {
        if (rawAccessToken.isBlank()) {
            return AuthSessionState.RevokedOrInvalid("empty_token")
        }

        val requestBuilder = HttpRequest.newBuilder(userEndpointUri)
            .GET()
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $rawAccessToken")
        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("apikey", apiKey)
        }

        val response = try {
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (_: HttpConnectTimeoutException) {
            return AuthSessionState.Unavailable("auth_connect_timeout")
        } catch (_: HttpTimeoutException) {
            return AuthSessionState.Unavailable("auth_request_timeout")
        } catch (_: ConnectException) {
            return AuthSessionState.Unavailable("auth_connection_refused")
        } catch (_: IOException) {
            return AuthSessionState.Unavailable("auth_io_error")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return AuthSessionState.Unavailable("auth_interrupted")
        }

        val status = response.statusCode()
        val bodyBytes = try {
            response.body().use { readBounded(it, MAX_RESPONSE_BYTES) }
        } catch (_: ResponseTooLargeException) {
            return AuthSessionState.Unavailable("auth_response_too_large")
        } catch (_: IOException) {
            return AuthSessionState.Unavailable("auth_body_read_error")
        }

        return when (status) {
            200 -> parseActiveUserResponse(bodyBytes)
            401, 403 -> AuthSessionState.RevokedOrInvalid("auth_rejected_http_$status")
            in 300..399 -> AuthSessionState.Unavailable("auth_unexpected_redirect_$status")
            in 500..599 -> AuthSessionState.Unavailable("auth_server_error_$status")
            else -> AuthSessionState.Unavailable("auth_unexpected_http_$status")
        }
    }

    private fun parseActiveUserResponse(bodyBytes: ByteArray): AuthSessionState {
        val root = try {
            mapper.readTree(bodyBytes)
        } catch (_: Exception) {
            return AuthSessionState.Unavailable("auth_invalid_json")
        }
        if (root == null || !root.isObject) {
            return AuthSessionState.Unavailable("auth_invalid_json_object")
        }

        val rawId = root.textOrNull("id")
            ?: return AuthSessionState.Unavailable("auth_missing_user_id")
        val userId = parseCanonicalUuid(rawId)
            ?: return AuthSessionState.Unavailable("auth_invalid_user_id")

        val anonymousNode = root.get("is_anonymous")
        val isAnonymous = when {
            anonymousNode == null || anonymousNode.isNull -> false
            anonymousNode.isBoolean -> anonymousNode.booleanValue()
            else -> return AuthSessionState.Unavailable("auth_invalid_anonymous_flag")
        }

        val confirmedAt = root.textOrNull("confirmed_at") ?: root.textOrNull("email_confirmed_at")
        val isConfirmed = confirmedAt != null && parseInstantOrNull(confirmedAt) != null

        val deletedAt = root.textOrNull("deleted_at")
        val isDeleted = deletedAt != null

        val bannedUntilRaw = root.textOrNull("banned_until")
        val isBanned = if (bannedUntilRaw == null) {
            false
        } else {
            val bannedUntil = parseInstantOrNull(bannedUntilRaw)
                ?: return AuthSessionState.Unavailable("auth_invalid_banned_until")
            bannedUntil.isAfter(clock.instant())
        }

        val verifiedFactorTypes = parseVerifiedMfaFactorTypes(root.get("factors"))
            ?: return AuthSessionState.Unavailable("auth_invalid_factors_payload")

        return AuthSessionState.Active(
            userId = userId,
            confirmed = isConfirmed,
            active = !isDeleted && !isBanned,
            anonymous = isAnonymous,
            verifiedMfaFactorTypes = verifiedFactorTypes,
        )
    }

    private fun parseVerifiedMfaFactorTypes(factorsNode: JsonNode?): Set<String>? {
        if (factorsNode == null || factorsNode.isNull) return emptySet()
        if (!factorsNode.isArray) return null
        val verified = linkedSetOf<String>()
        for (item in factorsNode) {
            if (!item.isObject) return null
            val status = item.textOrNull("status")?.lowercase()
            val factorType = item.textOrNull("factor_type")?.lowercase()
            if (status == "verified" && factorType != null) {
                verified.add(factorType)
            }
        }
        return verified
    }

    private fun JsonNode.textOrNull(field: String): String? {
        val node = get(field) ?: return null
        if (node.isNull) return null
        if (!node.isTextual) return null
        return node.asText().trim().takeIf { it.isNotEmpty() }
    }

    private fun parseCanonicalUuid(raw: String): UUID? = try {
        UUID.fromString(raw).takeIf { it.toString().equals(raw, ignoreCase = true) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseInstantOrNull(raw: String): Instant? = try {
        Instant.parse(raw)
    } catch (_: Exception) {
        null
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read == -1) break
            total += read
            if (total > maxBytes) throw ResponseTooLargeException()
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private class ResponseTooLargeException : RuntimeException()

    companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
