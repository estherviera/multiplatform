package com.nexora.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.auth.CachingJwksKeyProvider
import com.nexora.backend.adapter.auth.JwksFetcher
import com.nexora.backend.adapter.auth.JwtAuthConfig
import com.nexora.backend.adapter.auth.NimbusJwtAccessTokenVerifier
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.application.multitenant.CompanyQueryRepository
import com.nexora.backend.application.multitenant.MembershipManagementRepository
import com.nexora.backend.application.multitenant.MembershipManagementService
import com.nexora.backend.application.persistence.TransactionManager
import com.nexora.backend.application.persistence.TransactionalSession
import com.nexora.backend.bootstrap.AppConfig
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import com.nexora.backend.domain.multitenant.Company
import com.nexora.backend.domain.multitenant.CompanyInvitation
import com.nexora.backend.domain.multitenant.CompanyMembership
import com.nexora.backend.domain.multitenant.CompanyRole
import com.nexora.backend.domain.multitenant.MembershipStatus
import com.nexora.backend.domain.multitenant.PageRequest
import com.nexora.backend.domain.multitenant.PagedResult
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
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
 * Unit / HTTP contract tests with ephemeral ES256 test keys for Phase 2C.2 endpoints.
 * Real PostgreSQL + Supabase Auth + RLS + concurrency tests live in MembershipManagementIntegrationTest.
 */
class MembershipManagementRoutesUnitTest {
    private val json = ObjectMapper()
    private val issuer = "http://127.0.0.1:55421"
    private val audience = "authenticated"
    private val fixedNow = Instant.parse("2026-11-22T20:20:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun testKey() = ECKeyGenerator(Curve.P_256)
        .keyID("unit-kid-2c2")
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
    fun `all 2C2 endpoints require JWT and fail closed with 503 when persistence is disabled`() = testApplication {
        val key = testKey()
        val token = signToken(key)
        val cid = UUID.randomUUID()
        val uid = UUID.randomUUID()
        val iid = UUID.randomUUID()

        application {
            nexoraSaasModule(testAppConfig(), accessTokenVerifier = testVerifier(key))
        }

        // Missing JWT -> 401
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me/invitations").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me/invitations/$iid").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/me/invitations/$iid/accept").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/me/invitations/$iid/decline").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/companies/$cid/invitations").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/companies/$cid/invitations").status)
        assertEquals(HttpStatusCode.Unauthorized, client.patch("/api/v1/companies/$cid/memberships/$uid/role").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/companies/$cid/memberships/$uid/suspend").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/companies/$cid/memberships/$uid/reactivate").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/companies/$cid/admins/replace").status)

        // Valid JWT with persistence disabled -> 503 persistence_unavailable
        val resp = client.get("/api/v1/me/invitations") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        assertEquals("persistence_unavailable", json.readTree(resp.bodyAsText())["error"]["code"].asText())
    }

    @Test
    fun `validates UUIDs, roles, unknown JSON fields, and replaceAdmin invariants before touching DB`() = testApplication {
        val key = testKey()
        val token = signToken(key)
        val cid = UUID.randomUUID()
        val uid = UUID.randomUUID()
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
        val unusedQueryRepo = object : CompanyQueryRepository {
            override fun listActiveCompaniesForUser(session: TransactionalSession, userId: UUID, page: PageRequest): PagedResult<UserCompanySummary> = error("unused")
            override fun findActiveCompanyById(session: TransactionalSession, companyId: UUID): Company? = error("unused")
            override fun findActiveMembership(session: TransactionalSession, companyId: UUID, userId: UUID): CompanyMembership? = error("unused")
            override fun listVisibleMemberships(session: TransactionalSession, companyId: UUID, page: PageRequest): PagedResult<CompanyMembership> = error("unused")
        }
        val unusedMgmtRepo = object : MembershipManagementRepository {
            override fun lockActiveCompany(session: TransactionalSession, companyId: UUID): Boolean = error("unused")
            override fun isProfileEligible(session: TransactionalSession, userId: UUID): Boolean = error("unused")
            override fun findMembershipForUpdate(session: TransactionalSession, companyId: UUID, userId: UUID): CompanyMembership? = error("unused")
            override fun updateMembershipRole(session: TransactionalSession, companyId: UUID, userId: UUID, newRole: CompanyRole): CompanyMembership = error("unused")
            override fun updateMembershipStatus(session: TransactionalSession, companyId: UUID, userId: UUID, newStatus: MembershipStatus): CompanyMembership = error("unused")
            override fun findPendingInvitationForUpdate(session: TransactionalSession, companyId: UUID, inviteeUserId: UUID): CompanyInvitation? = error("unused")
            override fun createInvitation(session: TransactionalSession, companyId: UUID, inviteeUserId: UUID, invitedByUserId: UUID, role: CompanyRole, expiresAt: Instant): CompanyInvitation = error("unused")
            override fun listCompanyInvitations(session: TransactionalSession, companyId: UUID, page: PageRequest): PagedResult<CompanyInvitation> = error("unused")
            override fun listUserInvitations(session: TransactionalSession, userId: UUID, page: PageRequest): PagedResult<CompanyInvitation> = error("unused")
            override fun findInvitationByIdForUser(session: TransactionalSession, invitationId: UUID, userId: UUID): CompanyInvitation? = error("unused")
            override fun findInvitationForUpdate(session: TransactionalSession, invitationId: UUID): CompanyInvitation? = error("unused")
            override fun declineInvitation(session: TransactionalSession, invitationId: UUID): CompanyInvitation = error("unused")
            override fun revokeInvitation(session: TransactionalSession, invitationId: UUID): CompanyInvitation = error("unused")
            override fun acceptInvitationAtomically(session: TransactionalSession, invitationId: UUID, correlationId: String?): CompanyMembership? = error("unused")
            override fun replaceAdminAtomically(session: TransactionalSession, companyId: UUID, outgoingAdminUserId: UUID, successorUserId: UUID, outgoingNewRole: CompanyRole, correlationId: String?): com.nexora.backend.domain.multitenant.ReplaceAdminResult = error("unused")
            override fun setTransactionCorrelationId(session: TransactionalSession, correlationId: String?) = error("unused")
        }

        application {
            nexoraSaasModule(
                config = testAppConfig(),
                accessTokenVerifier = testVerifier(key),
                membershipManagementService = MembershipManagementService(neverTouchTx, unusedQueryRepo, unusedMgmtRepo, fixedClock),
            )
        }

        // Invalid role on invite -> 400
        val badRole = client.post("/api/v1/companies/$cid/invitations") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"inviteeUserId":"$uid","role":"superadmin"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badRole.status)
        assertEquals("validation_failed", json.readTree(badRole.bodyAsText())["error"]["code"].asText())

        // Unknown field injection on invite -> 400
        val extraField = client.post("/api/v1/companies/$cid/invitations") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"inviteeUserId":"$uid","role":"member","invitedByUserId":"$uid"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, extraField.status)

        // Replace admin with same outgoing and successor UUID -> 400
        val sameAdmin = client.post("/api/v1/companies/$cid/admins/replace") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"outgoingAdminUserId":"$uid","successorUserId":"$uid","outgoingNewRole":"trainer"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, sameAdmin.status)

        // Replace admin keeping outgoingNewRole as company_admin -> 400
        val badOutgoingRole = client.post("/api/v1/companies/$cid/admins/replace") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"outgoingAdminUserId":"$uid","successorUserId":"${UUID.randomUUID()}","outgoingNewRole":"company_admin"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badOutgoingRole.status)

        // Malformed invitation UUID -> 400
        val badInvId = client.post("/api/v1/me/invitations/not-a-uuid/accept") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, badInvId.status)
        assertEquals("invalid_invitation_id", json.readTree(badInvId.bodyAsText())["error"]["code"].asText())

        assertFalse(dbTouched, "Validation errors must never acquire a DB transaction")
    }
}
