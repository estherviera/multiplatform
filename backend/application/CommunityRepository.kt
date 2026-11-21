package com.nexora.backend.application

import com.nexora.backend.domain.Community
import java.util.UUID

interface CommunityRepository {
    fun save(community: Community)
    fun list(): List<Community>
    fun find(id: UUID): Community?
    fun update(community: Community): Boolean
    fun delete(id: UUID): Boolean
}
