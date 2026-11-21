package com.nexora.backend.adapter.http

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.application.CreateCommunity
import com.nexora.backend.application.ListCommunities
import com.nexora.backend.application.ManageCommunity
import com.nexora.backend.domain.Community
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

class CommunityHttpServer(
    port: Int,
    private val createCommunity: CreateCommunity,
    private val listCommunities: ListCommunities,
    private val manageCommunity: ManageCommunity,
) : AutoCloseable {
    private val json = ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val logger = Logger.getLogger(CommunityHttpServer::class.java.name)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
    }

    val port: Int get() = server.address.port

    fun start() = server.start()

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        try {
            val reply = try {
                route(exchange)
            } catch (error: Exception) {
                logger.log(Level.SEVERE, "Error al atender la solicitud", error)
                Reply(500, mapOf("error" to "internal_error"))
            }
            if (reply.status == 204) {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            val body = json.writeValueAsBytes(reply.body)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(reply.status, body.size.toLong())
            exchange.responseBody.write(body)
        } finally {
            exchange.close()
        }
    }

    private fun route(exchange: HttpExchange): Reply = when (exchange.requestURI.path) {
        "/health" -> when (exchange.requestMethod) {
            "GET" -> Reply(200, mapOf("status" to "ok"))
            else -> methodNotAllowed(exchange, "GET")
        }
        "/api/communities" -> when (exchange.requestMethod) {
            "GET" -> Reply(200, mapOf("items" to listCommunities.execute().map(::communityBody)))
            "POST" -> create(exchange)
            else -> methodNotAllowed(exchange, "GET, POST")
        }
        else -> individualCommunity(exchange)
    }

    private fun individualCommunity(exchange: HttpExchange): Reply {
        val prefix = "/api/communities/"
        val path = exchange.requestURI.path
        if (!path.startsWith(prefix)) return notFound()
        val rawId = path.removePrefix(prefix)
        if (rawId.isEmpty() || '/' in rawId) return notFound()
        val id = try {
            UUID.fromString(rawId).takeIf { it.toString().equals(rawId, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        } ?: return Reply(400, mapOf("error" to "invalid_community_id"))

        return when (exchange.requestMethod) {
            "GET" -> manageCommunity.find(id)?.let { Reply(200, communityBody(it)) } ?: notFound()
            "PUT" -> withCommunityName(exchange) { name ->
                manageCommunity.rename(id, name)?.let { Reply(200, communityBody(it)) } ?: notFound()
            }
            "DELETE" -> if (manageCommunity.delete(id)) Reply(204, null) else notFound()
            else -> methodNotAllowed(exchange, "GET, PUT, DELETE")
        }
    }

    private fun create(exchange: HttpExchange): Reply = withCommunityName(exchange) { name ->
        Reply(201, communityBody(createCommunity.execute(name)))
    }

    private fun withCommunityName(exchange: HttpExchange, action: (String) -> Reply): Reply {
        val body = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) return Reply(413, mapOf("error" to "payload_too_large"))

        val name = try {
            json.readTree(body)?.get("name")?.takeIf { it.isTextual }?.asText()
        } catch (_: JsonProcessingException) {
            null
        }
        if (name == null) return Reply(400, mapOf("error" to "invalid_community"))

        return try {
            action(name)
        } catch (_: IllegalArgumentException) {
            Reply(400, mapOf("error" to "invalid_community"))
        }
    }

    private fun notFound() = Reply(404, mapOf("error" to "not_found"))

    private fun methodNotAllowed(exchange: HttpExchange, allow: String): Reply {
        exchange.responseHeaders.set("Allow", allow)
        return Reply(405, mapOf("error" to "method_not_allowed"))
    }

    private fun communityBody(community: Community) =
        mapOf("id" to community.id.toString(), "name" to community.name)

    private data class Reply(val status: Int, val body: Any?)

    private companion object {
        const val MAX_BODY_BYTES = 4096
    }
}
