package com.nexora.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.auth.CachingJwksKeyProvider
import com.nexora.backend.adapter.auth.JwksFetcher
import com.nexora.backend.adapter.auth.JwtAuthConfig
import com.nexora.backend.adapter.auth.NimbusJwtAccessTokenVerifier
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.application.multitenant.CompanyQueryRepository
import com.nexora.backend.application.multitenant.CompanyQueryService
import com.nexora.backend.application.multitenant.ProfileRepository
import com.nexora.backend.application.multitenant.ProfileService
import com.nexora.backend.application.persistence.TransactionManager
import com.nexora.backend.application.persistence.TransactionalSession
import com.nexora.backend.bootstrap.AppConfig
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import com.nexora.backend.domain.multitenant.Company
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.PageRequest
import com.nexora.backend.domain.multitenant.PagedResult
import com.nexora.backend.domain.multitenant.Profile
import com.nexora.backend.domain.multitenant.UserCompanySummary
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit / HTTP contract tests for Phase 2C.1 multitenant endpoints (validation, auth enforcement,
 * unknown field rejection, and fail-closed 503 when persistence is disabled).
 *
 * Real tenant isolation and RLS assertions live in MultitenantApiPostgresIntegrationTest.
 */
class MultitenantRoutesUnitTest {
    private val json = ObjectMapper()
    private val issuer = "http://127.0.0.1:55421"
    private val audience = "authenticated"
    private val fixedNow = Instant.parse("2026-11-22T20:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun testKey() = ECKeyGenerator(Curve.P_256)
        .keyID("unit-kid-2c1")
        .keyUse(KeyUse.SIGNATURE)
        .keyOperations(setOf(KeyOperation.VERIFY))
        .algorithm(JWSAlgorithm.ES256)
        .generate()

    private fun testVerifier(key: com.nimbusds.jose.jwk.ECKey): NimbusJwtAccessTokenVerifier {
        val authConfig = JwtAuthConfig(issuer = issuer, audience = audience, jwksUri = URI("$issuer/.well-known/jwks.json"))
        val provider = CachingJwksKeyProvider(JwksFetcher { JWKSet(key.toPublicJWK()).toString() }, clock = fixedClock)
        return NimbusJwtAccessTokenVerifier(authConfig, provider, fixedClock)
    }

    private fun signToken(key: com.nimbusds.jose.jwk.ECKey, userId: UUID = UUID.randomUUID()): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(userId.toString())
            .issueTime(Date.from(fixedNow))
            .expirationTime(Date.from(fixedNow.plusSeconds(3600)))
            .claim("role", "authenticated")
            .claim("session_id", UUID.randomUUID().toString())
            .claim("aal", "aal1")
            .claim("is_anonymous", false)
            .build()
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType.JWT).build(),
            claims,
        )
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    private fun testAppConfig(): AppConfig = AppConfig.fromEnv(
        mapOf(
            "NEXORA_ENV" to "test",
            "NEXORA_AUTH_ISSUER" to issuer,
            "NEXORA_AUTH_AUDIENCE" to audience,
            "NEXORA_AUTH_JWKS_URL" to "$issuer/.well-known/jwks.json",
        ),
    )

    @Test
    fun `all 2C1 endpoints require JWT and fail closed with 503 when persistence is disabled`() = testApplication {
        val key = testKey()
        val token = signToken(key)
        val companyId = UUID.randomUUID()

        application {
            nexoraSaasModule(testAppConfig(), accessTokenVerifier = testVerifier(key))
        }

        val endpoints = listOf(
            "/api/v1/me/profile",
            "/api/v1/me/companies",
            "/api/v1/companies/$companyId",
            "/api/v1/companies/$companyId/membership",
            "/api/v1/companies/$companyId/memberships",
        )

        // Missing JWT -> 401 on every endpoint
        for (path in endpoints) {
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status, "GET $path must require JWT")
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.put("/api/v1/me/profile") {
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Test"}""")
            }.status,
        )

        // With valid JWT but persistence disabled -> 503 persistence_unavailable (health and /auth/me remain 200)
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
        )

        for (path in endpoints) {
            val resp = client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status, "GET $path must fail closed with 503")
            assertEquals("persistence_unavailable", json.readTree(resp.bodyAsText())["error"]["code"].asText())
        }
    }

    @Test
    fun `validates profile payloads, rejects unknown fields, invalid UUIDs, and out-of-range pagination before DB access`() =
        testApplication {
            val key = testKey()
            val token = signToken(key)
            var dbTouched = false

            val neverTouchTx = object : TransactionManager {
                override suspend fun <T> inAuthenticatedTransaction(
                    principal: AuthenticatedPrincipal,
                    readOnly: Boolean,
                    block: (TransactionalSession) -> T,
                ): T {
                    dbTouched = true
                    error("DB must not be touched on validation failure")
                }

                override suspend fun <T> inInfrastructureTransaction(
                    readOnly: Boolean,
                    block: (TransactionalSession) -> T,
                ): T {
                    dbTouched = true
                    error("DB must not be touched")
                }
            }

            val unusedProfileRepo = object : ProfileRepository {
                override fun findById(session: TransactionalSession, userId: UUID): Profile? = error("unused")
                override fun upsert(session: TransactionalSession, userId: UUID, displayName: String, avatarPath: String?): Profile =
                    error("unused")
            }
            val unusedCompanyRepo = object : CompanyQueryRepository {
                override fun listActiveCompaniesForUser(
                    session: TransactionalSession,
                    userId: UUID,
                    page: PageRequest,
                ): PagedResult<UserCompanySummary> = error("unused")
                override fun findActiveCompanyById(session: TransactionalSession, companyId: UUID): Company? = error("unused")
                override fun findActiveMembership(session: TransactionalSession, companyId: UUID, userId: UUID): CompanyMembership? =
                    error("unused")
                override fun listVisibleMemberships(
                    session: TransactionalSession,
                    companyId: UUID,
                    page: PageRequest,
                ): PagedResult<CompanyMembership> = error("unused")
            }

            application {
                nexoraSaasModule(
                    config = testAppConfig(),
                    accessTokenVerifier = testVerifier(key),
                    profileService = ProfileService(neverTouchTx, unusedProfileRepo),
                    companyQueryService = CompanyQueryService(neverTouchTx, unusedCompanyRepo),
                )
            }

            // Blank displayName -> 400 validation_failed
            val blankName = client.put("/api/v1/me/profile") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"   "}""")
            }
            assertEquals(HttpStatusCode.BadRequest, blankName.status)
            assertEquals("validation_failed", json.readTree(blankName.bodyAsText())["error"]["code"].asText())

            // Path traversal avatarPath -> 400 validation_failed
            val traversal = client.put("/api/v1/me/profile") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Alice","avatarPath":"../etc/passwd"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, traversal.status)
            assertEquals("validation_failed", json.readTree(traversal.bodyAsText())["error"]["code"].asText())

            // Unknown/forbidden field (client attempting to inject id or role) -> 400
            val extraField = client.put("/api/v1/me/profile") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Alice","id":"${UUID.randomUUID()}"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, extraField.status)

            // Malformed companyId UUID -> 400 invalid_company_id
            for (path in listOf(
                "/api/v1/companies/not-a-uuid",
                "/api/v1/companies/not-a-uuid/membership",
                "/api/v1/companies/not-a-uuid/memberships",
            )) {
                val resp = client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertEquals("invalid_company_id", json.readTree(resp.bodyAsText())["error"]["code"].asText())
            }

            // Out-of-range pagination -> 400 validation_failed
            for (query in listOf("limit=0", "limit=101", "offset=-1", "limit=abc")) {
                val resp = client.get("/api/v1/me/companies?$query") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                assertEquals(HttpStatusCode.BadRequest, resp.status, "Query '$query' must return 400")
                assertEquals("validation_failed", json.readTree(resp.bodyAsText())["error"]["code"].asText())
            }

            assertFalse(dbTouched, "Validation errors must be rejected before acquiring a DB transaction")
        }
}
