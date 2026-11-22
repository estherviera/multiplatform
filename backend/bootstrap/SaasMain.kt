package com.nexora.backend.bootstrap

import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.adapter.persistence.postgres.JdbcTransactionManager
import com.nexora.backend.adapter.persistence.postgres.PostgresCompanyQueryRepository
import com.nexora.backend.adapter.persistence.postgres.PostgresProfileRepository
import com.nexora.backend.adapter.persistence.postgres.PostgresRuntimePool
import com.nexora.backend.application.health.HealthService
import com.nexora.backend.application.health.ReadinessCheck
import com.nexora.backend.application.multitenant.CompanyQueryService
import com.nexora.backend.application.multitenant.ProfileService
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.nexora.backend.bootstrap.SaasMain")

fun main() {
    val config = AppConfig.fromEnv()
    val runtimePool = config.database?.let { dbConfig ->
        logger.info("Initializing PostgreSQL runtime pool with {}", dbConfig)
        PostgresRuntimePool.create(dbConfig, failFast = true)
    }

    val readinessChecks = linkedMapOf<String, ReadinessCheck>()
    var profileService: ProfileService? = null
    var companyQueryService: CompanyQueryService? = null
    if (runtimePool != null) {
        val transactionManager = JdbcTransactionManager(runtimePool)
        readinessChecks["database"] = runtimePool.readinessCheck(transactionManager)
        profileService = ProfileService(transactionManager, PostgresProfileRepository())
        companyQueryService = CompanyQueryService(transactionManager, PostgresCompanyQueryRepository())
    }

    val healthService = HealthService(
        serviceName = config.serviceName,
        environment = config.environment.wireName,
        readinessChecks = readinessChecks,
    )

    val server = embeddedServer(
        factory = Netty,
        host = config.host,
        port = config.port,
    ) {
        if (runtimePool != null) {
            monitor.subscribe(ApplicationStopped) {
                logger.info("Closing PostgreSQL runtime pool...")
                runtimePool.close()
            }
        }
        nexoraSaasModule(
            config = config,
            healthService = healthService,
            profileService = profileService,
            companyQueryService = companyQueryService,
        )
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("Stopping Nexora SaaS API...")
            try {
                server.stop(config.shutdownGracePeriodMs, config.shutdownTimeoutMs)
            } finally {
                runtimePool?.close()
            }
        },
    )

    logger.info("Starting Nexora SaaS API with {}", config)
    server.start(wait = true)
}
