package com.nexora.backend.application

import com.nexora.backend.domain.Community
import java.util.UUID

fun interface CreateCommunity {
    fun execute(name: String): Community
}

fun interface ListCommunities {
    fun execute(): List<Community>
}

class CommunityService(private val repository: CommunityRepository) : CreateCommunity, ListCommunities {
    override fun execute(name: String): Community {
        val community = Community(UUID.randomUUID(), name.trim())
        repository.save(community)
        return community
    }

    override fun execute(): List<Community> = repository.list()
}
