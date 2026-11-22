package com.nexora.backend.domain.auth

import java.time.Instant
import java.util.UUID

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
    val issuedAt: Instant? = null,
    val expiresAt: Instant,
)
