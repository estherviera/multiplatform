package com.nexora.backend.adapter.persistence

import com.nexora.backend.application.CommunityRepository
import com.nexora.backend.domain.Community
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

class SqliteCommunityRepository(database: Path) : CommunityRepository {
    private val url = "jdbc:sqlite:${database.toAbsolutePath()}"

    init {
        Files.createDirectories(database.toAbsolutePath().parent)
        connect().use { connection ->
            connection.createStatement().use {
                it.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS communities (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }
    }

    override fun save(community: Community) {
        connect().use { connection ->
            connection.prepareStatement("INSERT INTO communities (id, name) VALUES (?, ?)").use {
                it.setString(1, community.id.toString())
                it.setString(2, community.name)
                it.executeUpdate()
            }
        }
    }

    override fun list(): List<Community> = connect().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, name FROM communities ORDER BY rowid").use { rows ->
                buildList {
                    while (rows.next()) {
                        add(Community(UUID.fromString(rows.getString("id")), rows.getString("name")))
                    }
                }
            }
        }
    }

    override fun find(id: UUID): Community? = connect().use { connection ->
        connection.prepareStatement("SELECT name FROM communities WHERE id = ?").use { statement ->
            statement.setString(1, id.toString())
            statement.executeQuery().use { row ->
                if (row.next()) Community(id, row.getString("name")) else null
            }
        }
    }

    override fun update(community: Community): Boolean = connect().use { connection ->
        connection.prepareStatement("UPDATE communities SET name = ? WHERE id = ?").use {
            it.setString(1, community.name)
            it.setString(2, community.id.toString())
            it.executeUpdate() == 1
        }
    }

    override fun delete(id: UUID): Boolean = connect().use { connection ->
        connection.prepareStatement("DELETE FROM communities WHERE id = ?").use {
            it.setString(1, id.toString())
            it.executeUpdate() == 1
        }
    }

    private fun connect() = DriverManager.getConnection(url)
}
