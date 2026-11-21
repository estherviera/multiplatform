package com.nexora.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.nexora.backend.adapter.http.CommunityHttpServer
import com.nexora.backend.adapter.persistence.SqliteCommunityRepository
import com.nexora.backend.application.CommunityService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommunityBackendTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `communities survive reopening the database`() {
        val database = temporaryFolder.root.toPath().resolve("nexora.db")
        val service = CommunityService(SqliteCommunityRepository(database))
        assertEquals(emptyList(), service.execute())
        assertFailsWith<IllegalArgumentException> { service.execute("  ") }
        assertFailsWith<IllegalArgumentException> { service.execute("a".repeat(101)) }
        val community = service.execute("  Nexora  ")
        assertEquals("Nexora", community.name)
        assertEquals(listOf(community), CommunityService(SqliteCommunityRepository(database)).execute())
    }

    @Test
    fun `HTTP creates and lists persisted communities`() {
        val database = temporaryFolder.root.toPath().resolve("nexora.db")
        val service = CommunityService(SqliteCommunityRepository(database))
        val json = ObjectMapper()
        CommunityHttpServer(0, service, service).use { server ->
            server.start()
            val client = HttpClient.newHttpClient()
            fun request(path: String, body: String? = null): HttpResponse<String> {
                val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
                if (body != null) {
                    builder.header("Content-Type", "application/json")
                    builder.POST(HttpRequest.BodyPublishers.ofString(body))
                }
                return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            }

            val health = request("/health")
            assertEquals(200, health.statusCode())
            assertEquals("ok", json.readTree(health.body())["status"].asText())
            assertEquals(400, request("/api/communities", "{").statusCode())
            assertEquals(400, request("/api/communities", """{"name":" "}""").statusCode())
            assertEquals(413, request("/api/communities", "x".repeat(4097)).statusCode())
            val created = request("/api/communities", """{"name":"Acción"}""")
            assertEquals(201, created.statusCode())
            val listed = request("/api/communities")
            assertEquals(200, listed.statusCode())
            assertEquals(json.readTree(created.body()), json.readTree(listed.body())["items"].single())
            assertEquals("Acción", json.readTree(created.body())["name"].asText())
            assertEquals(404, request("/missing").statusCode())
            assertEquals(405, request("/health", "{}").statusCode())
        }
    }
}
