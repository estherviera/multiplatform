package com.nexora.backend

import com.nexora.backend.adapter.http.CommunityHttpServer
import com.nexora.backend.adapter.persistence.SqliteCommunityRepository
import com.nexora.backend.application.CommunityService
import java.nio.file.Path

fun main() {
    val port = (System.getenv("PORT") ?: "3000").toInt()
    val database = Path.of(System.getenv("DATABASE_PATH") ?: "data/nexora.db")
    val communities = CommunityService(SqliteCommunityRepository(database))
    val server = CommunityHttpServer(port, communities, communities)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    server.start()
    println("Nexora API: http://127.0.0.1:${server.port}")
}
