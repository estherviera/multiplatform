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
        CommunityHttpServer(0, service, service, service).use { server ->
            server.start()
            val client = HttpClient.newHttpClient()
            fun request(
                path: String,
                body: String? = null,
                method: String = if (body == null) "GET" else "POST",
            ): HttpResponse<String> {
                val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
                val publisher = if (body == null) {
                    HttpRequest.BodyPublishers.noBody()
                } else {
                    builder.header("Content-Type", "application/json")
                    HttpRequest.BodyPublishers.ofString(body)
                }
                builder.method(method, publisher)
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

            val id = json.readTree(created.body())["id"].asText()
            val path = "/api/communities/$id"
            val detail = request(path)
            assertEquals(200, detail.statusCode())
            assertEquals(json.readTree(created.body()), json.readTree(detail.body()))
            assertEquals(400, request("/api/communities/invalid").statusCode())
            assertEquals(400, request(path, """{"name":" "}""", "PUT").statusCode())
            assertEquals("Acción", json.readTree(request(path).body())["name"].asText())

            val updated = request(path, """{"name":" Aprender Kotlin "}""", "PUT")
            assertEquals(200, updated.statusCode())
            assertEquals(id, json.readTree(updated.body())["id"].asText())
            val reopened = CommunityService(SqliteCommunityRepository(database))
            assertEquals("Aprender Kotlin", reopened.execute().single().name)

            val deleted = request(path, method = "DELETE")
            assertEquals(204, deleted.statusCode())
            assertEquals("", deleted.body())
            assertEquals(404, request(path).statusCode())
            assertEquals(404, request(path, """{"name":"Otra"}""", "PUT").statusCode())
            assertEquals(404, request(path, method = "DELETE").statusCode())
            assertEquals(0, json.readTree(request("/api/communities").body())["items"].size())
            assertEquals(emptyList(), reopened.execute())
        }
    }
}
