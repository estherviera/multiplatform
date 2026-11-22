package com.nexora.backend.application.multitenant

import com.nexora.backend.application.persistence.TransactionManager
import com.nexora.backend.domain.auth.AuthenticatedPrincipal
import com.nexora.backend.domain.multitenant.Profile

class ProfileService(
    private val transactionManager: TransactionManager,
    private val profileRepository: ProfileRepository,
) {
    suspend fun getMyProfile(principal: AuthenticatedPrincipal): Profile =
        transactionManager.inAuthenticatedTransaction(principal, readOnly = true) { session ->
            profileRepository.findById(session, principal.userId)
                ?: throw ResourceNotFoundException(
                    code = "profile_not_found",
                    message = "Profile not found",
                )
        }

    suspend fun upsertMyProfile(
        principal: AuthenticatedPrincipal,
        rawDisplayName: String?,
        rawAvatarPath: String?,
    ): Profile {
        val violations = mutableListOf<ValidationViolation>()

        val normalizedDisplayName = rawDisplayName?.trim()
        if (normalizedDisplayName.isNullOrEmpty()) {
            violations.add(ValidationViolation("displayName", "must not be blank"))
        } else if (normalizedDisplayName.length > MAX_DISPLAY_NAME_LENGTH) {
            violations.add(ValidationViolation("displayName", "must be at most $MAX_DISPLAY_NAME_LENGTH characters"))
        }

        val normalizedAvatarPath = if (rawAvatarPath == null) {
            null
        } else {
            val trimmed = rawAvatarPath.trim()
            when {
                trimmed.isEmpty() -> {
                    violations.add(ValidationViolation("avatarPath", "must not be blank when provided; use null to clear"))
                    null
                }
                trimmed.length > MAX_AVATAR_PATH_LENGTH -> {
                    violations.add(ValidationViolation("avatarPath", "must be at most $MAX_AVATAR_PATH_LENGTH characters"))
                    null
                }
                ".." in trimmed -> {
                    violations.add(ValidationViolation("avatarPath", "must not contain path traversal segments"))
                    null
                }
                else -> trimmed
            }
        }

        if (violations.isNotEmpty()) {
            throw DomainValidationException(violations)
        }

        val validName = normalizedDisplayName!!
        return transactionManager.inAuthenticatedTransaction(principal, readOnly = false) { session ->
            profileRepository.upsert(session, principal.userId, validName, normalizedAvatarPath)
        }
    }

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 120
        const val MAX_AVATAR_PATH_LENGTH = 512
    }
}
