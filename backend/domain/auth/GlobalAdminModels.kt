package com.nexora.backend.domain.auth

import java.time.Instant
import java.util.UUID

/**
 * Immutable proof that a single privileged platform operation has passed:
 * - cryptographic JWT validation,
 * - exact server-configured global admin UUID & issuer verification,
 * - verified MFA level (`aal2`) with fresh second-factor challenge evidence (`amr`), and
 * - live server-side session, account, and enrolled MFA factor verification against Supabase Auth.
 *
 * Kept strictly separate from company-scoped roles (`company_admin`, `trainer`, `member`).
 */
data class GlobalAdminPrincipal(
    val userId: UUID,
    val issuer: String,
    val sessionId: UUID,
    val aal: String,
    val mfaMethod: String,
    val mfaVerifiedAt: Instant,
    val authorizedAt: Instant,
)

enum class GlobalAdminDenialReason(val wireCode: String) {
    GLOBAL_ADMIN_DISABLED("global_admin_disabled"),
    INVALID_TOKEN("invalid_token"),
    TOKEN_EXPIRED("token_expired"),
    ISSUER_MISMATCH("invalid_token"),
    NOT_GLOBAL_ADMIN("forbidden"),
    MISSING_SESSION_ID("session_required"),
    MFA_REQUIRED("mfa_required"),
    MFA_EVIDENCE_MISSING("mfa_reauthentication_required"),
    MFA_STEP_UP_EXPIRED("mfa_reauthentication_required"),
    MFA_FACTOR_NOT_VERIFIED("mfa_required"),
    SESSION_REVOKED_OR_INVALID("session_revoked"),
    ACCOUNT_NOT_ACTIVE_OR_UNCONFIRMED("account_inactive"),
    USER_IDENTITY_MISMATCH("invalid_token"),
}

sealed class GlobalAdminAuthorizationResult {
    data class Authorized(val principal: GlobalAdminPrincipal) : GlobalAdminAuthorizationResult()
    data class Denied(val reason: GlobalAdminDenialReason) : GlobalAdminAuthorizationResult()
    data class Unavailable(val reason: String) : GlobalAdminAuthorizationResult()
}
