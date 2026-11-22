package com.nexora.backend.bootstrap

import com.nexora.backend.adapter.http.ktor.nexoraSaasModule
import com.nexora.backend.application.health.HealthService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.nexora.backend.bootstrap.SaasMain")

fun main() {
    val config = AppConfig.fromEnv()
    val healthService = HealthService(
        serviceName = config.serviceName,
        environment = config.environment.wireName,
    )

    val server = embeddedServer(
        factory = Netty,
        host = config.host,
        port = config.port,
    ) {
        nexoraSaasModule(config, healthService)
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("Stopping Nexora SaaS API...")
            server.stop(config.shutdownGracePeriodMs, config.shutdownTimeoutMs)
        },
    )

    logger.info("Starting Nexora SaaS API with {}", config)
    server.start(wait = true)
}
