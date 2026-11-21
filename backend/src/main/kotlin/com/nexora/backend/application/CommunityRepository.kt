package com.nexora.backend.application

import com.nexora.backend.domain.Community

interface CommunityRepository {
    fun save(community: Community)
    fun list(): List<Community>
}
