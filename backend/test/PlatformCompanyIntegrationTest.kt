package com.nexora.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.auth.CachingJwksKeyProvider
import com.nexora.backend.adapter.auth.HttpJwksFetcher
import com.nexora.backend.adapter.auth.JwtAuthConfig
import com.nexora.backend.adapter.auth.NimbusJwtAccessTokenVerifier
import com.nexora.backend.adapter.auth.SupabaseAuthSessionVerifier
import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.adapter.persistence.postgres.DatabaseConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayConfig
import com.nexora.backend.adapter.persistence.postgres.FlywayMigrator
import com.nexora.backend.adapter.persistence.postgres.JdbcTransactionManager
import com.nexora.backend.adapter.persistence.postgres.PlatformDatabaseConfig
import com.nexora.backend.adapter.persistence.postgres.PostgresCompanyQueryRepository
import com.nexora.backend.adapter.persistence.postgres.PostgresPlatformCompanyRepository
import com.nexora.backend.adapter.persistence.postgres.PostgresPlatformPool
import com.nexora.backend.adapter.persistence.postgres.PostgresProfileRepository
import com.nexora.backend.adapter.persistence.postgres.PostgresRuntimePool
import com.nexora.backend.application.auth.DefaultGlobalAdminAuthorizer
import com.nexora.backend.application.auth.GlobalAdminPolicyConfig
import com.nexora.backend.application.multitenant.CompanyQueryService
import com.nexora.backend.application.multitenant.ProfileService
import com.nexora.backend.application.platform.PlatformCompanyService
import com.nexora.backend.application.platform.PlatformRateLimiter
import com.nexora.backend.bootstrap.AppConfig
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory

/**
 * Real E2E integration tests for Phase 2C.3B:
 * - Real local Supabase Auth GoTrue v2.197.0 with real TOTP enrollment/challenge/verify (`aal2`) and `logout`
 * - Real Ktor HTTP route `POST /api/v1/platform/companies`
 * - Real PostgreSQL 17 with separated roles: `app_runtime` (RLS tenant traffic), `app_platform` (least-privilege
 *   platform executor pool with zero direct table privileges), and `app_migrator` (Flyway V1–V7)
 *
 * Opt-in via `-Dnexora.test.localPostgres=true` with passwords injected ONLY via environment variables.
 */
class PlatformCompanyIntegrationTest {
    private val jdbcUrl = System.getProperty(
        "nexora.test.localPostgresUrl",
        System.getenv("NEXORA_RUNTIME_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:55422/postgres",
    )
    private val authBaseUrl = System.getProperty("nexora.test.localAuthUrl", "http://127.0.0.1:55421").trimEnd('/')
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    private fun requireEnabled() {
        val enabled = System.getProperty("nexora.test.localPostgres", "false").equals("true", ignoreCase = true)
        Assume.assumeTrue("Set -Dnexora.test.localPostgres=true to run Phase 2C.3B integration tests", enabled)
        require(jdbcUrl.startsWith("jdbc:postgresql://127.0.0.1:") || jdbcUrl.startsWith("jdbc:postgresql://localhost:")) {
            "Integration tests must target loopback PostgreSQL only, got '$jdbcUrl'"
        }
        val platformPw = System.getenv("NEXORA_PLATFORM_PASSWORD")
        Assume.assumeTrue("NEXORA_PLATFORM_PASSWORD env var is required for platform executor integration tests", !platformPw.isNullOrEmpty())
        val authHealthy = runCatching {
            http.send(
                HttpRequest.newBuilder(URI("$authBaseUrl/health")).GET().timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode() == 200
        }.getOrDefault(false)
        Assume.assumeTrue("Local Supabase Auth must be healthy at $authBaseUrl", authHealthy)
    }

    private fun runtimePassword(): String =
        System.getenv("NEXORA_RUNTIME_PASSWORD")?.takeIf { it.isNotEmpty() }
            ?: error("NEXORA_RUNTIME_PASSWORD env var is required")

    private fun platformPassword(): String =
        System.getenv("NEXORA_PLATFORM_PASSWORD")?.takeIf { it.isNotEmpty() }
            ?: error("NEXORA_PLATFORM_PASSWORD env var is required")

    private fun migratorPassword(): String =
        (System.getenv("FLYWAY_PASSWORD") ?: System.getenv("NEXORA_MIGRATION_PASSWORD"))?.takeIf { it.isNotEmpty() }
            ?: error("FLYWAY_PASSWORD or NEXORA_MIGRATION_PASSWORD env var is required")

    private fun runFlyway(): FlywayMigrator {
        val migrator = FlywayMigrator(
            FlywayConfig(
                jdbcUrl = jdbcUrl,
                username = "app_migrator",
                password = migratorPassword(),
                schema = "nexora",
                sslMode = "prefer",
            ),
        )
        migrator.migrate()
        assertTrue(migrator.validate().validationSuccessful)
        return migrator
    }

    private fun openMigratorConnection(): Connection = DriverManager.getConnection(
        jdbcUrl,
        Properties().apply {
            setProperty("user", "app_migrator")
            setProperty("password", migratorPassword())
            setProperty("sslmode", "prefer")
        },
    )

    private data class RealUser(
        val userId: UUID,
        val aal1Token: String,
        val password: String,
    )

    private fun signupRealUser(tag: String): RealUser {
        val email = "plat-$tag-${UUID.randomUUID().toString().take(8)}@nexora.local"
        val pw = "Plat-${UUID.randomUUID().toString().replace("-", "")}!A1"
        val resp = postJson("$authBaseUrl/signup", """{"email":"$email","password":"$pw"}""")
        assertEquals(200, resp.statusCode(), "Local Auth signup must succeed")
        val root = mapper.readTree(resp.body())
        return RealUser(
            userId = UUID.fromString(root["user"]["id"].asText()),
            aal1Token = root["access_token"].asText(),
            password = pw,
        )
    }

    private fun enrollAndVerifyTotp(aal1Token: String): Pair<String, String> {
        val enrollResp = postJson(
            "$authBaseUrl/factors",
            """{"factor_type":"totp","friendly_name":"nexora-2c3b"}""",
            bearerToken = aal1Token,
        )
        assertEquals(200, enrollResp.statusCode(), "TOTP enrollment must succeed on local GoTrue")
        val enrollJson = mapper.readTree(enrollResp.body())
        val factorId = enrollJson["id"].asText()
        val secret = enrollJson["totp"]["secret"].asText()

        val chalResp = postJson("$authBaseUrl/factors/$factorId/challenge", "{}", bearerToken = aal1Token)
        assertEquals(200, chalResp.statusCode())
        val challengeId = mapper.readTree(chalResp.body())["id"].asText()

        val code = computeTotpCode(secret, Instant.now())
        val verifyResp = postJson(
            "$authBaseUrl/factors/$factorId/verify",
            """{"challenge_id":"$challengeId","code":"$code"}""",
            bearerToken = aal1Token,
        )
        assertEquals(200, verifyResp.statusCode(), "TOTP verify must succeed")
        val aal2Token = mapper.readTree(verifyResp.body())["access_token"].asText()
        return aal2Token to secret
    }

    @Test
    fun `enforces strict SQL isolation between app_runtime and app_platform and preserves active-admin invariant`() = runBlocking {
        requireEnabled()
        runFlyway()

        PostgresRuntimePool.create(
            DatabaseConfig(jdbcUrl = jdbcUrl, username = "app_runtime", password = runtimePassword(), maxPoolSize = 1, minIdle = 1),
        ).use { runtimePool ->
            PostgresPlatformPool.create(
                PlatformDatabaseConfig(jdbcUrl = jdbcUrl, username = "app_platform", password = platformPassword(), maxPoolSize = 1, minIdle = 1),
            ).use { platformPool ->
                val runtimeTx = JdbcTransactionManager(runtimePool)
                val actor = AuthenticatedPrincipal(
                    userId = UUID.randomUUID(),
                    issuer = authBaseUrl,
                    audience = "authenticated",
                    sessionId = UUID.randomUUID(),
                    aal = "aal2",
                    expiresAt = Instant.now().plusSeconds(3600),
                )

                // 1. app_runtime CANNOT access nexora_platform schema, table, or functions (SQLSTATE 42501)
                val runtimeForbiddenSql = listOf(
                    "SELECT * FROM nexora_platform.idempotency_keys",
                    "SELECT * FROM nexora_platform.create_company_with_initial_admin('${actor.userId}'::uuid, 'k1', '${"a".repeat(64)}', 'hack-co', 'Hack Co', '${actor.userId}'::uuid, 'c1')",
                    "SELECT nexora_platform.record_platform_audit_event('${actor.userId}'::uuid, 'platform.company_create_denied', 'platform_companies', 'create', 'denied', 'c1', '{}'::jsonb)",
                    "INSERT INTO nexora.companies (slug, name) VALUES ('direct-hack', 'Direct Hack')",
                )
                for (sql in runtimeForbiddenSql) {
                    val err = assertFailsWith<PSQLException>("app_runtime must fail on: $sql") {
                        runtimeTx.inAuthenticatedTransaction(actor, readOnly = false) { session ->
                            session.connection.createStatement().use { it.execute(sql) }
                        }
                    }
                    assertEquals("42501", err.sqlState, "Expected 42501 for app_runtime on: $sql")
                }

                // 2. app_platform CANNOT directly read/write/alter ANY business/audit/idempotency table
                //    AND CANNOT forge 'succeeded' / 'platform.company_created' via record_platform_audit_event (SQLSTATE 42501)
                val platformForbiddenSql = listOf(
                    "SELECT count(*) FROM nexora.companies",
                    "INSERT INTO nexora.companies (slug, name) VALUES ('direct-plat', 'Direct Plat')",
                    "UPDATE nexora.companies SET name = 'Tampered'",
                    "DELETE FROM nexora.companies",
                    "SELECT count(*) FROM nexora.company_memberships",
                    "UPDATE nexora.company_memberships SET role = 'company_admin'",
                    "SELECT count(*) FROM nexora.audit_events",
                    "DELETE FROM nexora.audit_events",
                    "SELECT count(*) FROM nexora_platform.idempotency_keys",
                    "SELECT nexora_platform.record_platform_audit_event('${actor.userId}'::uuid, 'platform.company_created', 'company', '${UUID.randomUUID()}', 'succeeded', 'c1', '{}'::jsonb)",
                    "SELECT nexora_platform.record_platform_audit_event('${actor.userId}'::uuid, 'platform.company_create_denied', 'platform_companies', 'create', 'succeeded', 'c1', '{}'::jsonb)",
                )
                platformPool.dataSource.connection.use { conn ->
                    conn.autoCommit = false
                    for (sql in platformForbiddenSql) {
                        val sp = conn.setSavepoint("sp_plat")
                        val err = assertFailsWith<PSQLException>("app_platform must fail direct SQL: $sql") {
                            conn.createStatement().use { it.execute(sql) }
                        }
                        assertEquals("42501", err.sqlState, "Expected 42501 for app_platform on: $sql")
                        conn.rollback(sp)
                    }
                    conn.rollback()
                }

                // 3. Catalog verification: app_platform has no SUPERUSER/BYPASSRLS, no nexora USAGE, no PUBLIC/anon/authenticated access
                openMigratorConnection().use { conn ->
                    conn.createStatement().use { st ->
                        st.executeQuery(
                            """
                            SELECT r.rolsuper,
                                   r.rolbypassrls,
                                   r.rolcreaterole,
                                   r.rolcreatedb,
                                   has_schema_privilege('app_platform', 'nexora', 'USAGE'),
                                   has_schema_privilege('app_runtime', 'nexora_platform', 'USAGE'),
                                   has_function_privilege('public', 'nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text)', 'EXECUTE'),
                                   has_function_privilege('app_runtime', 'nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text)', 'EXECUTE'),
                                   has_function_privilege('app_platform', 'nexora_platform.create_company_with_initial_admin(uuid, text, text, text, text, uuid, text)', 'EXECUTE')
                            FROM pg_roles r
                            WHERE r.rolname = 'app_platform'
                            """.trimIndent(),
                        ).use { rs ->
                            assertTrue(rs.next())
                            for (i in 1..8) assertFalse(rs.getBoolean(i), "Security invariant col $i must be false")
                            assertTrue(rs.getBoolean(9), "app_platform must have EXECUTE on create_company_with_initial_admin")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `real TOTP global admin creates companies atomically with idempotency, concurrency, RLS, and full audit`() {
        requireEnabled()
        runFlyway()

        val globalAdmin = signupRealUser("ga")
        val initialAdmin = signupRealUser("initadm")
        val outsider = signupRealUser("out")
        val (gaAal2Token, totpSecret) = enrollAndVerifyTotp(globalAdmin.aal1Token)

        val createdCompanyIds = mutableListOf<UUID>()
        val createdProfileIds = listOf(initialAdmin.userId, outsider.userId)
        val slugPrefix = "co-${UUID.randomUUID().toString().take(8)}"

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)

        try {
            PostgresRuntimePool.create(
                DatabaseConfig(jdbcUrl = jdbcUrl, username = "app_runtime", password = runtimePassword(), maxPoolSize = 3, minIdle = 1),
            ).use { runtimePool ->
                PostgresPlatformPool.create(
                    PlatformDatabaseConfig(jdbcUrl = jdbcUrl, username = "app_platform", password = platformPassword(), maxPoolSize = 3, minIdle = 1),
                ).use { platformPool ->
                    val runtimeTx = JdbcTransactionManager(runtimePool)
                    val profileService = ProfileService(runtimeTx, PostgresProfileRepository())
                    val companyQueryService = CompanyQueryService(runtimeTx, PostgresCompanyQueryRepository())
                    val platformService = PlatformCompanyService(PostgresPlatformCompanyRepository(platformPool))

                    val authCfg = JwtAuthConfig(
                        issuer = authBaseUrl,
                        audience = "authenticated",
                        jwksUri = URI("$authBaseUrl/.well-known/jwks.json"),
                    )
                    val jwtVerifier = NimbusJwtAccessTokenVerifier(
                        config = authCfg,
                        keyProvider = CachingJwksKeyProvider(HttpJwksFetcher(authCfg.jwksUri)),
                    )
                    val globalAdminAuthorizer = DefaultGlobalAdminAuthorizer(
                        config = GlobalAdminPolicyConfig(
                            globalAdminUserId = globalAdmin.userId,
                            expectedIssuer = authBaseUrl,
                            mfaMaxAge = Duration.ofSeconds(300),
                            clockSkew = Duration.ofSeconds(30),
                            requireVerifiedMfaFactorInSessionCheck = true,
                        ),
                        accessTokenVerifier = jwtVerifier,
                        sessionVerifier = SupabaseAuthSessionVerifier(URI("$authBaseUrl/user"), Duration.ofSeconds(3)),
                    )
                    val appConfig = AppConfig.fromEnv(
                        mapOf(
                            "NEXORA_ENV" to "test",
                            "NEXORA_AUTH_ISSUER" to authBaseUrl,
                            "NEXORA_AUTH_JWKS_URL" to "$authBaseUrl/.well-known/jwks.json",
                        ),
                    )

                    testApplication {
                        application {
                            nexoraSaasModule(
                                config = appConfig,
                                accessTokenVerifier = jwtVerifier,
                                profileService = profileService,
                                companyQueryService = companyQueryService,
                                globalAdminAuthorizer = globalAdminAuthorizer,
                                platformCompanyService = platformService,
                                platformRateLimiter = PlatformRateLimiter(
                                    maxRequestsPerCaller = 50,
                                    maxGlobalRequests = 100,
                                    window = Duration.ofSeconds(60),
                                ),
                            )
                        }

                        // Provision profiles for initialAdmin and outsider via normal authenticated endpoint
                        assertEquals(
                            HttpStatusCode.OK,
                            client.put("/api/v1/me/profile") {
                                header(HttpHeaders.Authorization, "Bearer ${initialAdmin.aal1Token}")
                                contentType(ContentType.Application.Json)
                                setBody("""{"displayName":"Initial Admin"}""")
                            }.status,
                        )
                        assertEquals(
                            HttpStatusCode.OK,
                            client.put("/api/v1/me/profile") {
                                header(HttpHeaders.Authorization, "Bearer ${outsider.aal1Token}")
                                contentType(ContentType.Application.Json)
                                setBody("""{"displayName":"Outsider User"}""")
                            }.status,
                        )

                        // 1. Global admin with real aal1 token -> 403 mfa_required
                        val aal1Deny = client.post("/api/v1/platform/companies") {
                            header(HttpHeaders.Authorization, "Bearer ${globalAdmin.aal1Token}")
                            header("Idempotency-Key", "k-aal1-$slugPrefix")
                            header(HttpHeaders.XRequestId, "req-2c3b-aal1")
                            contentType(ContentType.Application.Json)
                            setBody("""{"slug":"$slugPrefix-aal1","name":"Blocked","initialAdminUserId":"${initialAdmin.userId}"}""")
                        }
                        assertEquals(HttpStatusCode.Forbidden, aal1Deny.status)
                        assertEquals("mfa_required", mapper.readTree(aal1Deny.bodyAsText())["error"]["code"].asText())

                        // 2. Ineligible initial admin (non-existent UUID) -> 409 initial_admin_not_eligible + complete rollback
                        val missingAdminResp = client.post("/api/v1/platform/companies") {
                            header(HttpHeaders.Authorization, "Bearer $gaAal2Token")
                            header("Idempotency-Key", "k-missing-$slugPrefix")
                            header(HttpHeaders.XRequestId, "req-2c3b-missing")
                            contentType(ContentType.Application.Json)
                            setBody("""{"slug":"$slugPrefix-orphan","name":"Orphan Co","initialAdminUserId":"${UUID.randomUUID()}"}""")
                        }
                        assertEquals(HttpStatusCode.Conflict, missingAdminResp.status)
                        assertEquals("initial_admin_not_eligible", mapper.readTree(missingAdminResp.bodyAsText())["error"]["code"].asText())

                        // 3. Valid real TOTP aal2 global admin creates company -> 201 Created!
                        val primarySlug = "$slugPrefix-main"
                        val primaryKey = "k-main-$slugPrefix"
                        val createdResp = client.post("/api/v1/platform/companies") {
                            header(HttpHeaders.Authorization, "Bearer $gaAal2Token")
                            header("Idempotency-Key", primaryKey)
                            header(HttpHeaders.XRequestId, "req-2c3b-create")
                            contentType(ContentType.Application.Json)
                            setBody("""{"slug":"$primarySlug","name":"Nexora Main Corp","initialAdminUserId":"${initialAdmin.userId}"}""")
                        }
                        assertEquals(HttpStatusCode.Created, createdResp.status)
                        val createdJson = mapper.readTree(createdResp.bodyAsText())
                        val companyId = UUID.fromString(createdJson["company"]["id"].asText())
                        createdCompanyIds.add(companyId)
                        assertEquals(primarySlug, createdJson["company"]["slug"].asText())
                        assertEquals("active", createdJson["company"]["status"].asText())
                        assertEquals(initialAdmin.userId.toString(), createdJson["initialAdminMembership"]["userId"].asText())
                        assertEquals("company_admin", createdJson["initialAdminMembership"]["role"].asText())
                        assertEquals("active", createdJson["initialAdminMembership"]["status"].asText())
                        assertFalse(createdJson["idempotencyReplayed"].asBoolean())

                        // 4. Verify real RLS visibility via app_runtime:
                        //    - initialAdmin sees the newly created company (200 OK)
                        //    - outsider CANNOT see it (404 Not Found)
                        //    - initialAdmin (now a company_admin!) still CANNOT create companies (403 forbidden)
                        assertEquals(
                            HttpStatusCode.OK,
                            client.get("/api/v1/companies/$companyId") {
                                header(HttpHeaders.Authorization, "Bearer ${initialAdmin.aal1Token}")
                            }.status,
                        )
                        assertEquals(
                            HttpStatusCode.NotFound,
                            client.get("/api/v1/companies/$companyId") {
                                header(HttpHeaders.Authorization, "Bearer ${outsider.aal1Token}")
                            }.status,
                        )
                        assertEquals(
                            HttpStatusCode.Forbidden,
                            client.post("/api/v1/platform/companies") {
                                header(HttpHeaders.Authorization, "Bearer ${initialAdmin.aal1Token}")
                                header("Idempotency-Key", "k-coadm-$slugPrefix")
                                contentType(ContentType.Application.Json)
                                setBody("""{"slug":"$slugPrefix-coadm","name":"No Priv","initialAdminUserId":"${initialAdmin.userId}"}""")
                            }.status,
                        )

                        // 5. Idempotent replay (e.g., retry after timeout / uncertain commit): same key + same body -> 200 OK
                        val replayResp = client.post("/api/v1/platform/companies") {
                            header(HttpHeaders.Authorization, "Bearer $gaAal2Token")
                            header("Idempotency-Key", primaryKey)
                            contentType(ContentType.Application.Json)
                            setBody("""{"slug":"$primarySlug","name":"Nexora Main Corp","initialAdminUserId":"${initialAdmin.userId}"}""")
                        }
                        assertEquals(HttpStatusCode.OK, replayResp.status)
                        assertEquals("true", replayResp.headers["X-Idempotency-Replayed"])
                        assertEquals(companyId.toString(), mapper.readTree(replayResp.bodyAsText())["company"]["id"].asText())

                        // 6. Same Idempotency-Key with DIFFERENT body -> 409 idempotency_key_conflict
                        val conflictKeyResp = client.post("/api/v1/platform/companies") {
                            header(HttpHeaders.Authorization, "Bearer $gaAal2Token")
                            header("Idempotency-Key", primaryKey)
                            contentType(ContentType.Application.Json)
                            setBody("""{"slug":"$slugPrefix-other","name":"Other Name","initialAdminUserId":"${initialAdmin.userId}"}""")
                        }
                        assertEquals(HttpStatusCode.Conflict, conflictKeyResp.status)
                        assertEquals("idempotency_key_conflict", mapper.readTree(conflictKeyResp.bodyAsText())["error"]["code"].asText())

                        // 7. Concurrent requests with SAME slug and DIFFERENT Idempotency-Keys:
                        //    EXACTLY ONE 201 Created and EXACTLY ONE 409 company_slug_conflict!
                        val raceSlug = "$slugPrefix-race"
                        val raceResults = coroutineScope {
                            listOf(
                                async {
                                    client.post("/api/v1/platform/companies") {
                                        header(HttpHeaders.Authorization, "Bearer $gaAal2Token")
                                        header("Idempotency-Key", "k-race-1-$slugPrefix")
                                        contentType(ContentType.Application.Json)
                                        setBody("""{"slug":"$raceSlug","name":"Race Co","initialAdminUserId":"${initialAdmin.userId}"}""")
                                    }
                                },
                                async {
                                    client.post("/api/v1/platform/companies") {
                                        header(HttpHeaders.Authorization, "Bearer $gaAal2Token")
                                        header("Idempotency-Key", "k-race-2-$slugPrefix")
                                        contentType(ContentType.Application.Json)
                                        setBody("""{"slug":"$raceSlug","name":"Race Co","initialAdminUserId":"${initialAdmin.userId}"}""")
                                    }
                                },
                            ).awaitAll()
                        }
                        assertEquals(setOf(HttpStatusCode.Created, HttpStatusCode.Conflict), raceResults.map { it.status }.toSet())
                        raceResults.first { it.status == HttpStatusCode.Created }.let { ok ->
                            createdCompanyIds.add(UUID.fromString(mapper.readTree(ok.bodyAsText())["company"]["id"].asText()))
                        }

                        // 8. Concurrent identical requests with SAME Idempotency-Key:
                        //    ONE 201 Created and ONE 200 OK (same company ID, zero duplicates)!
                        val sameKeySlug = "$slugPrefix-idem-race"
                        val sameKey = "k-idem-race-$slugPrefix"
                        val idemRace = coroutineScope {
                            listOf(
                                async {
                                    client.post("/api/v1/platform/companies") {
                                        header(HttpHeaders.Authorization, "Bearer $gaAal2Token")
                                        header("Idempotency-Key", sameKey)
                                        contentType(ContentType.Application.Json)
                                        setBody("""{"slug":"$sameKeySlug","name":"Idem Race Co","initialAdminUserId":"${initialAdmin.userId}"}""")
                                    }
                                },
                                async {
                                    client.post("/api/v1/platform/companies") {
                                        header(HttpHeaders.Authorization, "Bearer $gaAal2Token")
                                        header("Idempotency-Key", sameKey)
                                        contentType(ContentType.Application.Json)
                                        setBody("""{"slug":"$sameKeySlug","name":"Idem Race Co","initialAdminUserId":"${initialAdmin.userId}"}""")
                                    }
                                },
                            ).awaitAll()
                        }
                        assertEquals(setOf(HttpStatusCode.Created, HttpStatusCode.OK), idemRace.map { it.status }.toSet())
                        val id1 = UUID.fromString(mapper.readTree(idemRace[0].bodyAsText())["company"]["id"].asText())
                        val id2 = UUID.fromString(mapper.readTree(idemRace[1].bodyAsText())["company"]["id"].asText())
                        assertEquals(id1, id2)
                        createdCompanyIds.add(id1)

                        // 9. Real logout revokes global admin session immediately -> 401 session_revoked
                        val logoutResp = http.send(
                            HttpRequest.newBuilder(URI("$authBaseUrl/logout"))
                                .header("Authorization", "Bearer $gaAal2Token")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                        assertEquals(204, logoutResp.statusCode())

                        val afterLogout = client.post("/api/v1/platform/companies") {
                            header(HttpHeaders.Authorization, "Bearer $gaAal2Token")
                            header("Idempotency-Key", "k-after-logout-$slugPrefix")
                            header(HttpHeaders.XRequestId, "req-2c3b-logout")
                            contentType(ContentType.Application.Json)
                            setBody("""{"slug":"$slugPrefix-logout","name":"After Logout","initialAdminUserId":"${initialAdmin.userId}"}""")
                        }
                        assertEquals(HttpStatusCode.Unauthorized, afterLogout.status)
                        assertEquals("session_revoked", mapper.readTree(afterLogout.bodyAsText())["error"]["code"].asText())
                    }

                    // 10. Verify DB state & durable audit events (success, denial, failure, zero orphan companies)
                    openMigratorConnection().use { conn ->
                        // Orphan company rolled back completely
                        conn.prepareStatement("SELECT count(*) FROM nexora.companies WHERE slug = ?").use { ps ->
                            ps.setString(1, "$slugPrefix-orphan")
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                assertEquals(0, rs.getInt(1), "Rolled-back ineligible-admin company must not exist")
                            }
                        }
                        // Exactly 1 'platform.company_created' audit row for primary company (no duplicate on replay)
                        conn.prepareStatement(
                            "SELECT count(*) FROM nexora.audit_events WHERE company_id = ?::uuid AND action = 'platform.company_created' AND outcome = 'succeeded'",
                        ).use { ps ->
                            ps.setString(1, createdCompanyIds.first().toString())
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                assertEquals(1, rs.getInt(1))
                            }
                        }
                        // Separate-tx denial & failure audit rows persisted despite rollback
                        conn.prepareStatement(
                            "SELECT action, outcome FROM nexora.audit_events WHERE correlation_id IN ('req-2c3b-aal1', 'req-2c3b-missing', 'req-2c3b-logout')",
                        ).use { ps ->
                            val rows = ps.executeQuery().use { rs ->
                                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                            }
                            assertTrue(("platform.company_create_denied" to "denied") in rows)
                            assertTrue(("platform.company_create_failed" to "failed") in rows)
                        }
                    }

                    // 11. Verify zero tokens, TOTP secrets, or passwords leaked in logs
                    val allLogs = appender.list.joinToString("\n") { it.formattedMessage }
                    for (secret in listOf(gaAal2Token, globalAdmin.aal1Token, initialAdmin.aal1Token, outsider.aal1Token, totpSecret, globalAdmin.password)) {
                        assertFalse(allLogs.contains(secret), "Secret/token must never appear in logs")
                    }
                }
            }
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
            // Clean up ONLY synthetic test rows created during this test
            openMigratorConnection().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement("DELETE FROM nexora_platform.idempotency_keys WHERE actor_user_id = ?::uuid").use { ps ->
                        ps.setString(1, globalAdmin.userId.toString())
                        ps.executeUpdate()
                    }
                    conn.prepareStatement("DELETE FROM nexora.audit_events WHERE actor_user_id = ?::uuid OR correlation_id LIKE 'req-2c3b-%'").use { ps ->
                        ps.setString(1, globalAdmin.userId.toString())
                        ps.executeUpdate()
                    }
                    for (cid in createdCompanyIds) {
                        conn.prepareStatement("UPDATE nexora.companies SET status = 'suspended' WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, cid.toString())
                            ps.executeUpdate()
                        }
                        conn.prepareStatement("DELETE FROM nexora.company_memberships WHERE company_id = ?::uuid").use { ps ->
                            ps.setString(1, cid.toString())
                            ps.executeUpdate()
                        }
                        conn.prepareStatement("DELETE FROM nexora.companies WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, cid.toString())
                            ps.executeUpdate()
                        }
                    }
                    for (pid in createdProfileIds) {
                        conn.prepareStatement("DELETE FROM nexora.profiles WHERE id = ?::uuid").use { ps ->
                            ps.setString(1, pid.toString())
                            ps.executeUpdate()
                        }
                    }
                    conn.commit()
                } catch (_: Throwable) {
                    runCatching { conn.rollback() }
                }
            }
        }
    }

    private fun postJson(url: String, body: String, bearerToken: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(5))
        if (bearerToken != null) builder.header("Authorization", "Bearer $bearerToken")
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun computeTotpCode(base32Secret: String, instant: Instant): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = base32Secret.uppercase().filter { it in alphabet }
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>((clean.length * 5) / 8)
        for (ch in clean) {
            buffer = (buffer shl 5) or alphabet.indexOf(ch)
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        val counterBytes = ByteBuffer.allocate(8).putLong(instant.epochSecond / 30L).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(out.toByteArray(), "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }
}
