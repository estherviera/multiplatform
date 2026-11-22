package com.nexora.backend.application.auth

import com.nexora.backend.domain.auth.GlobalAdminAuthorizationResult
import com.nexora.backend.domain.auth.GlobalAdminDenialReason
import com.nexora.backend.domain.auth.GlobalAdminPrincipal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Policy configuration for platform-level global administrator authorization.
 * Disabled by default when `globalAdminUserId` is absent.
 */
data class GlobalAdminPolicyConfig(
    val globalAdminUserId: UUID? = null,
    val expectedIssuer: String? = null,
    val mfaMaxAge: Duration = DEFAULT_MFA_MAX_AGE,
    val clockSkew: Duration = DEFAULT_CLOCK_SKEW,
    val requireVerifiedMfaFactorInSessionCheck: Boolean = true,
) {
    val enabled: Boolean
        get() = globalAdminUserId != null

    init {
        if (globalAdminUserId != null) {
            require(!expectedIssuer.isNullOrBlank()) {
                "expectedIssuer is required when globalAdminUserId is configured"
            }
        }
        require(mfaMaxAge >= MIN_MFA_MAX_AGE && mfaMaxAge <= MAX_MFA_MAX_AGE) {
            "mfaMaxAge must be between ${MIN_MFA_MAX_AGE.seconds}s and ${MAX_MFA_MAX_AGE.seconds}s"
        }
        require(!clockSkew.isNegative && clockSkew <= MAX_CLOCK_SKEW) {
            "clockSkew must be between 0s and ${MAX_CLOCK_SKEW.seconds}s"
        }
    }

    override fun toString(): String =
        "GlobalAdminPolicyConfig(enabled=$enabled, mfaMaxAgeSeconds=${mfaMaxAge.seconds}, " +
            "clockSkewSeconds=${clockSkew.seconds}, requireVerifiedMfaFactor=$requireVerifiedMfaFactorInSessionCheck)"

    companion object {
        val MIN_MFA_MAX_AGE: Duration = Duration.ofSeconds(30)
        val DEFAULT_MFA_MAX_AGE: Duration = Duration.ofSeconds(300)
        val MAX_MFA_MAX_AGE: Duration = Duration.ofSeconds(900)
        val DEFAULT_CLOCK_SKEW: Duration = Duration.ofSeconds(30)
        val MAX_CLOCK_SKEW: Duration = Duration.ofSeconds(120)

        fun disabled(): GlobalAdminPolicyConfig = GlobalAdminPolicyConfig()
    }
}

/**
 * Live session and account state verified server-side against the identity provider.
 */
sealed class AuthSessionState {
    data class Active(
        val userId: UUID,
        val confirmed: Boolean,
        val active: Boolean,
        val anonymous: Boolean,
        val verifiedMfaFactorTypes: Set<String> = emptySet(),
    ) : AuthSessionState()

    data class RevokedOrInvalid(val reason: String) : AuthSessionState()

    data class Unavailable(val reason: String) : AuthSessionState()
}

/**
 * Port for live server-side verification of an access token's session and user account.
 */
fun interface AuthSessionVerifier {
    fun verifyActiveSession(rawAccessToken: String): AuthSessionState
}

/**
 * Reusable authorization boundary for sensitive platform-level global operations.
 * Completely independent of company-scoped memberships and RLS roles.
 */
interface GlobalAdminAuthorizer {
    suspend fun authorize(
        rawAccessToken: String?,
        correlationId: String? = null,
        operation: String = "global_admin",
    ): GlobalAdminAuthorizationResult
}

class DefaultGlobalAdminAuthorizer(
    private val config: GlobalAdminPolicyConfig,
    private val accessTokenVerifier: AccessTokenVerifier?,
    private val sessionVerifier: AuthSessionVerifier?,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GlobalAdminAuthorizer {

    override suspend fun authorize(
        rawAccessToken: String?,
        correlationId: String?,
        operation: String,
    ): GlobalAdminAuthorizationResult {
        val safeOp = sanitizeLogTag(operation)
        val safeCorr = sanitizeLogTag(correlationId ?: "none")

        // 1. Fail closed by default if global admin capability is not configured on the server.
        val configuredAdminId = config.globalAdminUserId
        val expectedIssuer = config.expectedIssuer
        if (configuredAdminId == null || expectedIssuer.isNullOrBlank()) {
            logDenied(safeOp, safeCorr, GlobalAdminDenialReason.GLOBAL_ADMIN_DISABLED)
            return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.GLOBAL_ADMIN_DISABLED)
        }

        // 2. Require configured cryptographic verifier and live session verifier (fail closed).
        if (accessTokenVerifier == null || sessionVerifier == null) {
            logUnavailable(safeOp, safeCorr, "global_admin_verifier_not_configured")
            return GlobalAdminAuthorizationResult.Unavailable("global_admin_verifier_not_configured")
        }

        val token = rawAccessToken?.trim()
        if (token.isNullOrEmpty()) {
            logDenied(safeOp, safeCorr, GlobalAdminDenialReason.INVALID_TOKEN)
            return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.INVALID_TOKEN)
        }

        // 3. Cryptographic JWT validation (signature, allowed asymmetric alg, iss, aud, exp, nbf, role, sub UUID).
        val principal = when (val verification = withContext(ioDispatcher) { accessTokenVerifier.verify(token) }) {
            is TokenVerificationResult.Valid -> verification.principal
            is TokenVerificationResult.Invalid -> {
                val reason = if (verification.reason == TokenRejectionReason.TOKEN_EXPIRED) {
                    GlobalAdminDenialReason.TOKEN_EXPIRED
                } else {
                    GlobalAdminDenialReason.INVALID_TOKEN
                }
                logDenied(safeOp, safeCorr, reason)
                return GlobalAdminAuthorizationResult.Denied(reason)
            }
            is TokenVerificationResult.Unavailable -> {
                logUnavailable(safeOp, safeCorr, verification.reason)
                return GlobalAdminAuthorizationResult.Unavailable(verification.reason)
            }
        }

        // 4. Exact issuer match against server configuration.
        if (principal.issuer != expectedIssuer) {
            logDenied(safeOp, safeCorr, GlobalAdminDenialReason.ISSUER_MISMATCH)
            return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.ISSUER_MISMATCH)
        }

        // 5. Constant-time comparison between cryptographically verified `sub` and `NEXORA_GLOBAL_ADMIN_SUB`.
        //    Non-global users are rejected here before any outbound call to Supabase Auth.
        if (!constantTimeUuidEquals(principal.userId, configuredAdminId)) {
            logDenied(safeOp, safeCorr, GlobalAdminDenialReason.NOT_GLOBAL_ADMIN)
            return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.NOT_GLOBAL_ADMIN)
        }

        // 6. Mandatory session binding (`session_id` must be present in the verified JWT).
        val sessionId = principal.sessionId
        if (sessionId == null) {
            logDenied(safeOp, safeCorr, GlobalAdminDenialReason.MISSING_SESSION_ID)
            return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.MISSING_SESSION_ID)
        }

        // 7. Require verified MFA Authenticator Assurance Level `aal2`.
        if (principal.aal != REQUIRED_AAL) {
            logDenied(safeOp, safeCorr, GlobalAdminDenialReason.MFA_REQUIRED, sessionId = sessionId)
            return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.MFA_REQUIRED)
        }

        // 8. Verify fresh second-factor challenge evidence from cryptographically signed `amr` claims.
        //    NEVER rely on `iat` (token refresh updates `iat` without repeating MFA).
        val now = clock.instant()
        val maxFutureTimestamp = now.plus(config.clockSkew)
        val mfaEntries = principal.amr.filter { entry ->
            entry.method.lowercase() in ALLOWED_MFA_AMR_METHODS
        }
        if (mfaEntries.isEmpty()) {
            logDenied(safeOp, safeCorr, GlobalAdminDenialReason.MFA_EVIDENCE_MISSING, sessionId = sessionId)
            return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.MFA_EVIDENCE_MISSING)
        }

        val validNonFutureMfa = mfaEntries.filter { !it.authenticatedAt.isAfter(maxFutureTimestamp) }
        val latestMfa = validNonFutureMfa.maxByOrNull { it.authenticatedAt }
        if (latestMfa == null) {
            logDenied(safeOp, safeCorr, GlobalAdminDenialReason.MFA_EVIDENCE_MISSING, sessionId = sessionId)
            return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.MFA_EVIDENCE_MISSING)
        }

        if (latestMfa.authenticatedAt.plus(config.mfaMaxAge).isBefore(now)) {
            logDenied(safeOp, safeCorr, GlobalAdminDenialReason.MFA_STEP_UP_EXPIRED, sessionId = sessionId)
            return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.MFA_STEP_UP_EXPIRED)
        }

        // 9. Verify live session validity, account status, and enrolled verified MFA factor on Supabase Auth.
        val sessionState = withContext(ioDispatcher) { sessionVerifier.verifyActiveSession(token) }
        when (sessionState) {
            is AuthSessionState.Unavailable -> {
                logUnavailable(safeOp, safeCorr, sessionState.reason)
                return GlobalAdminAuthorizationResult.Unavailable(sessionState.reason)
            }
            is AuthSessionState.RevokedOrInvalid -> {
                logDenied(safeOp, safeCorr, GlobalAdminDenialReason.SESSION_REVOKED_OR_INVALID, sessionId = sessionId)
                return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.SESSION_REVOKED_OR_INVALID)
            }
            is AuthSessionState.Active -> {
                if (!constantTimeUuidEquals(sessionState.userId, principal.userId)) {
                    logDenied(safeOp, safeCorr, GlobalAdminDenialReason.USER_IDENTITY_MISMATCH, sessionId = sessionId)
                    return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.USER_IDENTITY_MISMATCH)
                }
                if (!sessionState.confirmed || !sessionState.active || sessionState.anonymous) {
                    logDenied(safeOp, safeCorr, GlobalAdminDenialReason.ACCOUNT_NOT_ACTIVE_OR_UNCONFIRMED, sessionId = sessionId)
                    return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.ACCOUNT_NOT_ACTIVE_OR_UNCONFIRMED)
                }
                if (config.requireVerifiedMfaFactorInSessionCheck &&
                    sessionState.verifiedMfaFactorTypes.none { it.lowercase() in ALLOWED_VERIFIED_FACTOR_TYPES }
                ) {
                    logDenied(safeOp, safeCorr, GlobalAdminDenialReason.MFA_FACTOR_NOT_VERIFIED, sessionId = sessionId)
                    return GlobalAdminAuthorizationResult.Denied(GlobalAdminDenialReason.MFA_FACTOR_NOT_VERIFIED)
                }
            }
        }

        val authorizedPrincipal = GlobalAdminPrincipal(
            userId = principal.userId,
            issuer = principal.issuer,
            sessionId = sessionId,
            aal = REQUIRED_AAL,
            mfaMethod = latestMfa.method.lowercase(),
            mfaVerifiedAt = latestMfa.authenticatedAt,
            authorizedAt = now,
        )
        logger.info(
            "Global admin authorized [operation={}, correlationId={}, sessionId={}, aal={}, mfaMethod={}]",
            safeOp,
            safeCorr,
            sessionId,
            authorizedPrincipal.aal,
            authorizedPrincipal.mfaMethod,
        )
        return GlobalAdminAuthorizationResult.Authorized(authorizedPrincipal)
    }

    private fun logDenied(
        operation: String,
        correlationId: String,
        reason: GlobalAdminDenialReason,
        sessionId: UUID? = null,
    ) {
        logger.warn(
            "Global admin denied [operation={}, correlationId={}, sessionId={}, reason={}]",
            operation,
            correlationId,
            sessionId ?: "none",
            reason.name,
        )
    }

    private fun logUnavailable(
        operation: String,
        correlationId: String,
        reason: String,
    ) {
        logger.error(
            "Global admin verification unavailable [operation={}, correlationId={}, reason={}]",
            operation,
            correlationId,
            sanitizeLogTag(reason),
        )
    }

    private fun constantTimeUuidEquals(left: UUID, right: UUID): Boolean =
        MessageDigest.isEqual(
            left.toString().toByteArray(StandardCharsets.UTF_8),
            right.toString().toByteArray(StandardCharsets.UTF_8),
        )

    private fun sanitizeLogTag(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._:-]"), "_").take(64).ifEmpty { "none" }

    companion object {
        const val REQUIRED_AAL = "aal2"

        /**
         * Strong second-factor AMR method values emitted by Supabase Auth (GoTrue).
         * First-factor methods (`password`, `otp`, `oauth`, `magiclink`, `recovery`, `invite`, `anonymous`)
         * are explicitly excluded.
         */
        val ALLOWED_MFA_AMR_METHODS: Set<String> = setOf(
            "totp",
            "mfa/totp",
            "webauthn",
            "mfa/webauthn",
        )

        /**
         * Strong enrolled MFA factor types returned by Supabase Auth `/user` (`factors[].factor_type`).
         */
        val ALLOWED_VERIFIED_FACTOR_TYPES: Set<String> = setOf(
            "totp",
            "webauthn",
        )

        private val logger = LoggerFactory.getLogger("com.nexora.backend.application.auth.GlobalAdminAuthorizer")
    }
}
