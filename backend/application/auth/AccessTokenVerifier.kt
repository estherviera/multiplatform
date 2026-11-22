package com.nexora.backend.application.auth

import com.nexora.backend.domain.auth.AuthenticatedPrincipal

enum class TokenRejectionReason(val wireCode: String) {
    MALFORMED_TOKEN("invalid_token"),
    UNSUPPORTED_ALGORITHM("invalid_token"),
    MISSING_KEY_ID("invalid_token"),
    UNKNOWN_KEY("invalid_token"),
    INCOMPATIBLE_KEY("invalid_token"),
    INVALID_SIGNATURE("invalid_token"),
    INVALID_ISSUER("invalid_token"),
    INVALID_AUDIENCE("invalid_token"),
    MISSING_EXPIRATION("invalid_token"),
    TOKEN_EXPIRED("token_expired"),
    TOKEN_NOT_YET_VALID("invalid_token"),
    MISSING_OR_INVALID_SUBJECT("invalid_token"),
    DISALLOWED_TOKEN_CLASS("invalid_token"),
    ANONYMOUS_SESSION_NOT_ALLOWED("invalid_token"),
}

sealed class TokenVerificationResult {
    data class Valid(val principal: AuthenticatedPrincipal) : TokenVerificationResult()
    data class Invalid(val reason: TokenRejectionReason) : TokenVerificationResult()
    data class Unavailable(val reason: String) : TokenVerificationResult()
}

fun interface AccessTokenVerifier {
    fun verify(rawToken: String): TokenVerificationResult
}
