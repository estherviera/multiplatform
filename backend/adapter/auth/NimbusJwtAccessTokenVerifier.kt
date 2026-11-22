package com.nexora.backend.adapter.auth

import com.nexora.backend.application.auth.AccessTokenVerifier
import com.nexora.backend.application.auth.TokenRejectionReason
import com.nexora.backend.application.auth.TokenVerificationResult
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import com.nexora.backend.domain.auth.AuthenticationMethodReference
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.JWTParser
import com.nimbusds.jwt.SignedJWT
import java.text.ParseException
import java.time.Clock
import java.time.Instant
import java.util.UUID

class NimbusJwtAccessTokenVerifier(
    private val config: JwtAuthConfig,
    private val keyProvider: JwksKeyProvider,
    private val clock: Clock = Clock.systemUTC(),
) : AccessTokenVerifier {

    override fun verify(rawToken: String): TokenVerificationResult {
        if (rawToken.isBlank() || rawToken.length > MAX_TOKEN_LENGTH || rawToken.count { it == '.' } != 2) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.MALFORMED_TOKEN)
        }

        val parsedJwt = try {
            JWTParser.parse(rawToken)
        } catch (_: ParseException) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.MALFORMED_TOKEN)
        }
        val signedJwt = parsedJwt as? SignedJWT
            ?: return TokenVerificationResult.Invalid(TokenRejectionReason.UNSUPPORTED_ALGORITHM)

        val header = signedJwt.header
        val algorithm = header.algorithm
        if (algorithm == null || algorithm.name !in config.allowedAlgorithms) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.UNSUPPORTED_ALGORITHM)
        }

        val tokenType = header.type?.type
        if (tokenType != null && !tokenType.equals("JWT", ignoreCase = true) && !tokenType.equals("at+jwt", ignoreCase = true)) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.DISALLOWED_TOKEN_CLASS)
        }

        val kid = header.keyID?.trim()
        if (kid.isNullOrEmpty()) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.MISSING_KEY_ID)
        }

        val jwk = try {
            keyProvider.findKey(kid)
        } catch (ex: JwksUnavailableException) {
            return TokenVerificationResult.Unavailable(ex.reason)
        } ?: return TokenVerificationResult.Invalid(TokenRejectionReason.UNKNOWN_KEY)

        val verifier = buildVerifier(jwk, algorithm)
            ?: return TokenVerificationResult.Invalid(TokenRejectionReason.INCOMPATIBLE_KEY)

        val signatureValid = try {
            signedJwt.verify(verifier)
        } catch (_: Exception) {
            false
        }
        if (!signatureValid) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.INVALID_SIGNATURE)
        }

        val claims = try {
            signedJwt.jwtClaimsSet
        } catch (_: ParseException) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.MALFORMED_TOKEN)
        }

        return validateClaims(claims)
    }

    private fun buildVerifier(jwk: JWK, algorithm: JWSAlgorithm): JWSVerifier? {
        if (jwk.isPrivate) return null
        if (jwk.keyUse != null && jwk.keyUse != KeyUse.SIGNATURE) return null
        val ops = jwk.keyOperations
        if (ops != null && KeyOperation.VERIFY !in ops) return null
        if (jwk.algorithm != null && jwk.algorithm.name != algorithm.name) return null

        return try {
            when (algorithm) {
                JWSAlgorithm.ES256 -> ecVerifier(jwk, Curve.P_256)
                JWSAlgorithm.ES384 -> ecVerifier(jwk, Curve.P_384)
                JWSAlgorithm.ES512 -> ecVerifier(jwk, Curve.P_521)
                JWSAlgorithm.RS256,
                JWSAlgorithm.RS384,
                JWSAlgorithm.RS512,
                JWSAlgorithm.PS256 -> rsaVerifier(jwk)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun ecVerifier(jwk: JWK, expectedCurve: Curve): JWSVerifier? {
        val ecKey = jwk as? ECKey ?: return null
        if (ecKey.curve != expectedCurve) return null
        return ECDSAVerifier(ecKey)
    }

    private fun rsaVerifier(jwk: JWK): JWSVerifier? {
        val rsaKey = jwk as? RSAKey ?: return null
        if (rsaKey.size() < MIN_RSA_KEY_BITS) return null
        return RSASSAVerifier(rsaKey)
    }

    private fun validateClaims(claims: JWTClaimsSet): TokenVerificationResult {
        val issuer = claims.issuer
        if (issuer == null || issuer != config.issuer) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.INVALID_ISSUER)
        }

        val audiences = claims.audience ?: emptyList()
        if (config.audience !in audiences) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.INVALID_AUDIENCE)
        }

        val now = clock.instant()
        val exp = claims.expirationTime?.toInstant()
            ?: return TokenVerificationResult.Invalid(TokenRejectionReason.MISSING_EXPIRATION)
        if (!exp.plusSeconds(config.clockSkewSeconds).isAfter(now)) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.TOKEN_EXPIRED)
        }

        val nbf = claims.notBeforeTime?.toInstant()
        if (nbf != null && nbf.minusSeconds(config.clockSkewSeconds).isAfter(now)) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.TOKEN_NOT_YET_VALID)
        }

        val roleClaim = claims.getClaim("role")
        val role = roleClaim as? String
        if (role == null || role != config.expectedRole) {
            return TokenVerificationResult.Invalid(TokenRejectionReason.DISALLOWED_TOKEN_CLASS)
        }

        val anonymousClaim = claims.getClaim("is_anonymous")
        if (anonymousClaim != null) {
            val isAnonymous = anonymousClaim as? Boolean
                ?: return TokenVerificationResult.Invalid(TokenRejectionReason.DISALLOWED_TOKEN_CLASS)
            if (isAnonymous && !config.allowAnonymous) {
                return TokenVerificationResult.Invalid(TokenRejectionReason.ANONYMOUS_SESSION_NOT_ALLOWED)
            }
        }

        val sub = claims.subject?.trim()
        val userId = parseCanonicalUuid(sub)
            ?: return TokenVerificationResult.Invalid(TokenRejectionReason.MISSING_OR_INVALID_SUBJECT)

        val sessionIdClaim = claims.getClaim("session_id")
        val sessionId = when (sessionIdClaim) {
            null -> null
            is String -> {
                val trimmed = sessionIdClaim.trim()
                if (trimmed.isEmpty()) {
                    null
                } else {
                    parseCanonicalUuid(trimmed)
                        ?: return TokenVerificationResult.Invalid(TokenRejectionReason.DISALLOWED_TOKEN_CLASS)
                }
            }
            else -> return TokenVerificationResult.Invalid(TokenRejectionReason.DISALLOWED_TOKEN_CLASS)
        }

        val aalClaim = claims.getClaim("aal")
        val aal = when (aalClaim) {
            null -> null
            is String -> aalClaim.trim().takeIf { it.isNotEmpty() }
            else -> return TokenVerificationResult.Invalid(TokenRejectionReason.DISALLOWED_TOKEN_CLASS)
        }

        val amr = parseAmrClaim(claims.getClaim("amr"))
            ?: return TokenVerificationResult.Invalid(TokenRejectionReason.DISALLOWED_TOKEN_CLASS)

        val issuedAt = claims.issueTime?.toInstant()

        return TokenVerificationResult.Valid(
            AuthenticatedPrincipal(
                userId = userId,
                issuer = issuer,
                audience = config.audience,
                sessionId = sessionId,
                aal = aal,
                amr = amr,
                issuedAt = issuedAt,
                expiresAt = exp,
            ),
        )
    }

    private fun parseAmrClaim(rawAmr: Any?): List<AuthenticationMethodReference>? {
        if (rawAmr == null) return emptyList()
        val list = rawAmr as? List<*> ?: return null
        if (list.size > MAX_AMR_ENTRIES) return null
        return buildList(list.size) {
            for (entry in list) {
                val map = entry as? Map<*, *> ?: return null
                val method = (map["method"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
                    ?: return null
                val tsNumber = map["timestamp"] as? Number ?: return null
                val tsDouble = tsNumber.toDouble()
                if (!tsDouble.isFinite() || tsDouble <= 0.0 || tsDouble > MAX_EPOCH_SECONDS.toDouble()) {
                    return null
                }
                val tsLong = tsNumber.toLong()
                if (tsLong <= 0L) return null
                add(
                    AuthenticationMethodReference(
                        method = method,
                        authenticatedAt = Instant.ofEpochSecond(tsLong),
                    ),
                )
            }
        }
    }

    private fun parseCanonicalUuid(raw: String?): UUID? {
        if (raw.isNullOrBlank()) return null
        return try {
            UUID.fromString(raw).takeIf { it.toString().equals(raw, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val MAX_TOKEN_LENGTH = 8_192
        const val MIN_RSA_KEY_BITS = 2048
        const val MAX_AMR_ENTRIES = 32
        const val MAX_EPOCH_SECONDS = 4_102_444_800L // Year 2100 UTC
    }
}
