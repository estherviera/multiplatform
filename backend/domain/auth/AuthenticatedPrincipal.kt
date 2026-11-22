package com.nexora.backend.domain.auth

import java.time.Instant
import java.util.UUID

/**
 * Cryptographically signed Authentication Method Reference (AMR) entry from Supabase Auth JWTs.
 */
data class AuthenticationMethodReference(
    val method: String,
    val authenticatedAt: Instant,
)

/**
 * Verified, immutable user identity derived exclusively from a cryptographically
 * validated Supabase Auth access token.
 *
 * Business roles, company memberships, and course permissions are intentionally
 * excluded: they must always be resolved server-side from persistent state.
 */
data class AuthenticatedPrincipal(
    val userId: UUID,
    val issuer: String,
    val audience: String,
    val sessionId: UUID? = null,
    val aal: String? = null,
    val amr: List<AuthenticationMethodReference> = emptyList(),
    val issuedAt: Instant? = null,
    val expiresAt: Instant,
)
