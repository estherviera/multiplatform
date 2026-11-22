package com.nexora.backend

import com.nexora.backend.adapter.auth.CachingJwksKeyProvider
import com.nexora.backend.adapter.auth.JwksFetcher
import com.nexora.backend.adapter.auth.JwksUnavailableException
import com.nexora.backend.adapter.auth.JwtAuthConfig
import com.nexora.backend.adapter.auth.NimbusJwtAccessTokenVerifier
import com.nexora.backend.application.auth.TokenRejectionReason
import com.nexora.backend.application.auth.TokenVerificationResult
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for JWT verification and JWKS caching/rotation using ephemeral
 * asymmetric test keys and a controllable clock (independent of HTTP and Supabase).
 */
class JwtVerifierTest {
    private val issuer = "http://127.0.0.1:55421"
    private val audience = "authenticated"
    private val baseTime = Instant.parse("2026-11-22T18:00:00Z")

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = current
    }

    private fun generateEcKey(
        kid: String,
        use: KeyUse = KeyUse.SIGNATURE,
        alg: JWSAlgorithm = JWSAlgorithm.ES256,
        ops: Set<KeyOperation>? = if (use == KeyUse.SIGNATURE) setOf(KeyOperation.VERIFY) else null,
    ): ECKey {
        val builder = ECKeyGenerator(Curve.P_256)
            .keyID(kid)
            .keyUse(use)
            .algorithm(alg)
        if (ops != null) builder.keyOperations(ops)
        return builder.generate()
    }

    private fun generateRsaKey(kid: String): RSAKey =
        RSAKeyGenerator(2048)
            .keyID(kid)
            .keyUse(KeyUse.SIGNATURE)
            .keyOperations(setOf(KeyOperation.VERIFY))
            .algorithm(JWSAlgorithm.RS256)
            .generate()

    private fun signEc(
        key: ECKey,
        claims: JWTClaimsSet,
        kidOverride: String? = key.keyID,
        alg: JWSAlgorithm = JWSAlgorithm.ES256,
        typ: JOSEObjectType? = JOSEObjectType.JWT,
    ): String {
        val headerBuilder = JWSHeader.Builder(alg)
        if (kidOverride != null) headerBuilder.keyID(kidOverride)
        if (typ != null) headerBuilder.type(typ)
        val jwt = SignedJWT(headerBuilder.build(), claims)
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    private fun validClaims(
        userId: UUID = UUID.randomUUID(),
        sessionId: UUID = UUID.randomUUID(),
        iss: String? = issuer,
        aud: String? = audience,
        exp: Instant? = baseTime.plusSeconds(3600),
        nbf: Instant? = null,
        role: Any? = "authenticated",
        isAnonymous: Any? = false,
        aal: String? = "aal1",
    ): JWTClaimsSet {
        val builder = JWTClaimsSet.Builder()
            .subject(userId.toString())
            .issueTime(Date.from(baseTime))
        if (iss != null) builder.issuer(iss)
        if (aud != null) builder.audience(aud)
        if (exp != null) builder.expirationTime(Date.from(exp))
        if (nbf != null) builder.notBeforeTime(Date.from(nbf))
        if (role != null) builder.claim("role", role)
        if (isAnonymous != null) builder.claim("is_anonymous", isAnonymous)
        builder.claim("session_id", sessionId.toString())
        if (aal != null) builder.claim("aal", aal)
        return builder.build()
    }

    private fun jwksJson(vararg keys: JWK): String =
        JWKSet(keys.map { it.toPublicJWK() }).toString()

    private fun defaultConfig(allowedAlgs: Set<String> = setOf("ES256"), skewSeconds: Long = 30L) =
        JwtAuthConfig(
            issuer = issuer,
            audience = audience,
            jwksUri = URI("$issuer/.well-known/jwks.json"),
            allowedAlgorithms = allowedAlgs,
            clockSkewSeconds = skewSeconds,
        )

    @Test
    fun `accepts valid ES256 user access token and builds immutable principal`() {
        val key = generateEcKey("kid-1")
        val clock = MutableClock(baseTime)
        val provider = CachingJwksKeyProvider(JwksFetcher { jwksJson(key) }, clock = clock)
        val verifier = NimbusJwtAccessTokenVerifier(defaultConfig(), provider, clock)

        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val exp = baseTime.plusSeconds(3600)
        val token = signEc(key, validClaims(userId = userId, sessionId = sessionId, exp = exp))

        val result = assertIs<TokenVerificationResult.Valid>(verifier.verify(token))
        assertEquals(userId, result.principal.userId)
        assertEquals(issuer, result.principal.issuer)
        assertEquals(audience, result.principal.audience)
        assertEquals(sessionId, result.principal.sessionId)
        assertEquals("aal1", result.principal.aal)
        assertEquals(exp, result.principal.expiresAt)
    }

    @Test
    fun `accepts valid RS256 token only when RS256 is explicitly allowed`() {
        val rsaKey = generateRsaKey("rsa-1")
        val clock = MutableClock(baseTime)
        val provider = CachingJwksKeyProvider(JwksFetcher { jwksJson(rsaKey) }, clock = clock)

        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rsa-1").type(JOSEObjectType.JWT).build(),
            validClaims(),
        )
        jwt.sign(RSASSASigner(rsaKey))
        val serialized = jwt.serialize()

        val esOnlyVerifier = NimbusJwtAccessTokenVerifier(defaultConfig(setOf("ES256")), provider, clock)
        val rejected = assertIs<TokenVerificationResult.Invalid>(esOnlyVerifier.verify(serialized))
        assertEquals(TokenRejectionReason.UNSUPPORTED_ALGORITHM, rejected.reason)

        val esAndRsVerifier = NimbusJwtAccessTokenVerifier(defaultConfig(setOf("ES256", "RS256")), provider, clock)
        assertIs<TokenVerificationResult.Valid>(esAndRsVerifier.verify(serialized))
    }

    @Test
    fun `supports key rotation with overlap and immediate cache purge on key retirement`() {
        val key1 = generateEcKey("kid-1")
        val key2 = generateEcKey("kid-2")
        val clock = MutableClock(baseTime)
        var currentJwks = jwksJson(key1)
        val fetchCount = AtomicInteger(0)

        val provider = CachingJwksKeyProvider(
            fetcher = JwksFetcher {
                fetchCount.incrementAndGet()
                currentJwks
            },
            cacheTtl = Duration.ofMinutes(10),
            minRefreshInterval = Duration.ofSeconds(30),
            clock = clock,
        )
        val verifier = NimbusJwtAccessTokenVerifier(defaultConfig(), provider, clock)

        val token1 = signEc(key1, validClaims())
        val token2 = signEc(key2, validClaims())

        // Initial fetch caches kid-1
        assertIs<TokenVerificationResult.Valid>(verifier.verify(token1))
        assertEquals(1, fetchCount.get())

        // Rotate JWKS to publish both kid-1 and kid-2 (overlap window)
        currentJwks = jwksJson(key1, key2)
        clock.current = baseTime.plusSeconds(31)

        // Unknown kid-2 triggers refresh and both tokens succeed during overlap
        assertIs<TokenVerificationResult.Valid>(verifier.verify(token2))
        assertEquals(2, fetchCount.get())
        assertIs<TokenVerificationResult.Valid>(verifier.verify(token1))
        assertEquals(2, fetchCount.get())

        // Urgent retirement of kid-1 + cache purge rejects kid-1 immediately while kid-2 stays valid
        currentJwks = jwksJson(key2)
        provider.invalidateCache()

        val retired = assertIs<TokenVerificationResult.Invalid>(verifier.verify(token1))
        assertEquals(TokenRejectionReason.UNKNOWN_KEY, retired.reason)
        assertEquals(3, fetchCount.get())
        assertIs<TokenVerificationResult.Valid>(verifier.verify(token2))
    }

    @Test
    fun `rate limits JWKS refresh on unknown kids and fails closed on network outage`() {
        val key1 = generateEcKey("kid-1")
        val clock = MutableClock(baseTime)
        val fetchCount = AtomicInteger(0)
        var failNetwork = false

        val provider = CachingJwksKeyProvider(
            fetcher = JwksFetcher {
                fetchCount.incrementAndGet()
                if (failNetwork) throw JwksUnavailableException("network_down")
                jwksJson(key1)
            },
            cacheTtl = Duration.ofMinutes(10),
            minRefreshInterval = Duration.ofSeconds(30),
            clock = clock,
        )
        val verifier = NimbusJwtAccessTokenVerifier(defaultConfig(), provider, clock)

        // Warm cache with valid key
        assertIs<TokenVerificationResult.Valid>(verifier.verify(signEc(key1, validClaims())))
        assertEquals(1, fetchCount.get())

        // Flood of unknown kids within minRefreshInterval must NOT trigger repeated JWKS fetches
        val unknownKey = generateEcKey("unknown-kid")
        val unknownToken = signEc(unknownKey, validClaims())
        repeat(5) {
            val res = assertIs<TokenVerificationResult.Invalid>(verifier.verify(unknownToken))
            assertEquals(TokenRejectionReason.UNKNOWN_KEY, res.reason)
        }
        assertEquals(1, fetchCount.get())

        // After cache invalidation + network outage, verifier fails closed with Unavailable
        provider.invalidateCache()
        failNetwork = true
        val unavailable = assertIs<TokenVerificationResult.Unavailable>(
            verifier.verify(signEc(key1, validClaims())),
        )
        assertEquals("network_down", unavailable.reason)
    }

    @Test
    fun `enforces exact exp and nbf boundaries with configured clock skew`() {
        val key = generateEcKey("kid-1")
        val skew = 30L
        val exp = baseTime.plusSeconds(100)
        val nbf = baseTime.plusSeconds(10)
        val token = signEc(key, validClaims(exp = exp, nbf = nbf))

        val clock = MutableClock(baseTime)
        val provider = CachingJwksKeyProvider(JwksFetcher { jwksJson(key) }, clock = clock)
        val verifier = NimbusJwtAccessTokenVerifier(defaultConfig(skewSeconds = skew), provider, clock)

        // Before nbf - skew -> rejected
        clock.current = nbf.minusSeconds(skew + 1)
        assertEquals(
            TokenRejectionReason.TOKEN_NOT_YET_VALID,
            assertIs<TokenVerificationResult.Invalid>(verifier.verify(token)).reason,
        )

        // Exactly at nbf - skew -> accepted
        clock.current = nbf.minusSeconds(skew)
        assertIs<TokenVerificationResult.Valid>(verifier.verify(token))

        // 1 second before exp + skew -> accepted
        clock.current = exp.plusSeconds(skew - 1)
        assertIs<TokenVerificationResult.Valid>(verifier.verify(token))

        // Exactly at exp + skew -> rejected as expired
        clock.current = exp.plusSeconds(skew)
        assertEquals(
            TokenRejectionReason.TOKEN_EXPIRED,
            assertIs<TokenVerificationResult.Invalid>(verifier.verify(token)).reason,
        )
    }

    @Test
    fun `rejects T01 negative cases including unsigned, HS256, wrong signature, claims, and service_role`() {
        val key = generateEcKey("kid-1")
        val otherKey = generateEcKey("kid-1") // same kid, different key material
        val encKey = generateEcKey("kid-enc", use = KeyUse.ENCRYPTION)
        val clock = MutableClock(baseTime)
        val provider = CachingJwksKeyProvider(JwksFetcher { jwksJson(key, encKey) }, clock = clock)
        val verifier = NimbusJwtAccessTokenVerifier(defaultConfig(), provider, clock)

        // Malformed / opaque refresh token
        assertEquals(
            TokenRejectionReason.MALFORMED_TOKEN,
            assertIs<TokenVerificationResult.Invalid>(verifier.verify("opaque-refresh-token")).reason,
        )

        // Unsigned alg=none
        val plainJwt = PlainJWT(validClaims()).serialize()
        assertEquals(
            TokenRejectionReason.UNSUPPORTED_ALGORITHM,
            assertIs<TokenVerificationResult.Invalid>(verifier.verify(plainJwt)).reason,
        )

        // Symmetric HS256
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hsJwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID("kid-1").build(), validClaims())
        hsJwt.sign(MACSigner(secret))
        assertEquals(
            TokenRejectionReason.UNSUPPORTED_ALGORITHM,
            assertIs<TokenVerificationResult.Invalid>(verifier.verify(hsJwt.serialize())).reason,
        )

        // Missing kid
        assertEquals(
            TokenRejectionReason.MISSING_KEY_ID,
            assertIs<TokenVerificationResult.Invalid>(verifier.verify(signEc(key, validClaims(), kidOverride = null))).reason,
        )

        // Incompatible JWK use=enc
        assertEquals(
            TokenRejectionReason.INCOMPATIBLE_KEY,
            assertIs<TokenVerificationResult.Invalid>(verifier.verify(signEc(encKey, validClaims()))).reason,
        )

        // Wrong signature (signed with different key claiming kid-1)
        assertEquals(
            TokenRejectionReason.INVALID_SIGNATURE,
            assertIs<TokenVerificationResult.Invalid>(verifier.verify(signEc(otherKey, validClaims()))).reason,
        )

        // Wrong issuer (another project)
        assertEquals(
            TokenRejectionReason.INVALID_ISSUER,
            assertIs<TokenVerificationResult.Invalid>(
                verifier.verify(signEc(key, validClaims(iss = "https://other.supabase.co/auth/v1"))),
            ).reason,
        )

        // Wrong audience
        assertEquals(
            TokenRejectionReason.INVALID_AUDIENCE,
            assertIs<TokenVerificationResult.Invalid>(
                verifier.verify(signEc(key, validClaims(aud = "other-audience"))),
            ).reason,
        )

        // Missing exp
        assertEquals(
            TokenRejectionReason.MISSING_EXPIRATION,
            assertIs<TokenVerificationResult.Invalid>(
                verifier.verify(signEc(key, validClaims(exp = null))),
            ).reason,
        )

        // service_role token rejected
        assertEquals(
            TokenRejectionReason.DISALLOWED_TOKEN_CLASS,
            assertIs<TokenVerificationResult.Invalid>(
                verifier.verify(signEc(key, validClaims(role = "service_role"))),
            ).reason,
        )

        // anon key token rejected
        val anonClaims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .expirationTime(Date.from(baseTime.plusSeconds(3600)))
            .claim("role", "anon")
            .build()
        assertEquals(
            TokenRejectionReason.DISALLOWED_TOKEN_CLASS,
            assertIs<TokenVerificationResult.Invalid>(verifier.verify(signEc(key, anonClaims))).reason,
        )

        // Anonymous Auth session rejected by default
        assertEquals(
            TokenRejectionReason.ANONYMOUS_SESSION_NOT_ALLOWED,
            assertIs<TokenVerificationResult.Invalid>(
                verifier.verify(signEc(key, validClaims(isAnonymous = true))),
            ).reason,
        )

        // Non-UUID subject rejected
        val badSubClaims = JWTClaimsSet.Builder(validClaims())
            .subject("not-a-uuid")
            .build()
        assertEquals(
            TokenRejectionReason.MISSING_OR_INVALID_SUBJECT,
            assertIs<TokenVerificationResult.Invalid>(verifier.verify(signEc(key, badSubClaims))).reason,
        )
    }
}
