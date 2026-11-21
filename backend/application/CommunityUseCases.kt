package com.nexora.backend.application

import com.nexora.backend.domain.Community
import java.util.UUID

fun interface CreateCommunity {
    fun execute(name: String): Community
}

fun interface ListCommunities {
    fun execute(): List<Community>
}

interface ManageCommunity {
    fun find(id: UUID): Community?
    fun rename(id: UUID, name: String): Community?
    fun delete(id: UUID): Boolean
}

class CommunityService(private val repository: CommunityRepository) :
    CreateCommunity, ListCommunities, ManageCommunity {
    override fun execute(name: String): Community {
        val community = Community(UUID.randomUUID(), name.trim())
        repository.save(community)
        return community
    }

    override fun execute(): List<Community> = repository.list()

    override fun find(id: UUID): Community? = repository.find(id)

    override fun rename(id: UUID, name: String): Community? {
        val community = Community(id, name.trim())
        return community.takeIf { repository.update(it) }
    }

    override fun delete(id: UUID): Boolean = repository.delete(id)
}
