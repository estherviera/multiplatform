package com.nexora.backend.application.platform

import com.nexora.backend.application.multitenant.DomainConflictException
import com.nexora.backend.application.multitenant.DomainValidationException
import com.nexora.backend.application.multitenant.ValidationViolation
import com.nexora.backend.domain.auth.GlobalAdminPrincipal
import com.nexora.backend.domain.multitenant.Company
import com.nexora.backend.domain.multitenant.CompanyMembership
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import org.slf4j.LoggerFactory

data class CreateCompanyCommand(
    val slug: String?,
    val name: String?,
    val initialAdminUserId: UUID,
    val idempotencyKey: String?,
)

data class PlatformCompanyCreationResult(
    val company: Company,
    val initialAdminMembership: CompanyMembership,
    val idempotencyReplayed: Boolean,
)

interface PlatformCompanyRepository {
    suspend fun createCompanyWithInitialAdmin(
        actorUserId: UUID,
        idempotencyKey: String,
        requestHash: String,
        slug: String,
        name: String,
        initialAdminUserId: UUID,
        correlationId: String?,
    ): PlatformCompanyCreationResult

    suspend fun recordPlatformAuditEvent(
        actorUserId: UUID?,
        action: String,
        targetType: String,
        targetId: String,
        outcome: String,
        correlationId: String?,
        metadataJson: String,
    )
}

data class RateLimitDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long = 0L,
)

/**
 * Thread-safe sliding-window rate limiter protecting sensitive platform endpoints
 * against abusive retries and brute-force attempts (both per-caller and global buckets).
 */
class PlatformRateLimiter(
    private val maxRequestsPerCaller: Int = DEFAULT_MAX_REQUESTS_PER_CALLER,
    private val maxGlobalRequests: Int = DEFAULT_MAX_GLOBAL_REQUESTS,
    private val window: Duration = DEFAULT_WINDOW,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lock = Any()
    private val globalTimestamps = ArrayDeque<Instant>()
    private val callerTimestamps = LinkedHashMap<String, ArrayDeque<Instant>>()

    init {
        require(maxRequestsPerCaller in 1..1_000) { "maxRequestsPerCaller must be between 1 and 1000" }
        require(maxGlobalRequests >= maxRequestsPerCaller && maxGlobalRequests <= 10_000) {
            "maxGlobalRequests must be between $maxRequestsPerCaller and 10000"
        }
        require(window >= Duration.ofSeconds(1) && window <= Duration.ofHours(1)) {
            "rate limit window must be between 1s and 3600s"
        }
    }

    fun tryAcquire(callerKey: String): RateLimitDecision = synchronized(lock) {
        val now = clock.instant()
        val cutoff = now.minus(window)

        evictExpired(globalTimestamps, cutoff)
        if (globalTimestamps.size >= maxGlobalRequests) {
            val oldest = globalTimestamps.peekFirst() ?: now
            val retryAfter = maxOf(1L, Duration.between(now, oldest.plus(window)).seconds + 1L)
            return@synchronized RateLimitDecision(allowed = false, retryAfterSeconds = retryAfter)
        }

        if (callerTimestamps.size > MAX_TRACKED_CALLERS) {
            val iter = callerTimestamps.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                evictExpired(entry.value, cutoff)
                if (entry.value.isEmpty()) iter.remove()
            }
            while (callerTimestamps.size > MAX_TRACKED_CALLERS && callerTimestamps.isNotEmpty()) {
                val firstKey = callerTimestamps.keys.first()
                callerTimestamps.remove(firstKey)
            }
        }

        val deque = callerTimestamps.getOrPut(callerKey) { ArrayDeque() }
        evictExpired(deque, cutoff)
        if (deque.size >= maxRequestsPerCaller) {
            val oldest = deque.peekFirst() ?: now
            val retryAfter = maxOf(1L, Duration.between(now, oldest.plus(window)).seconds + 1L)
            return@synchronized RateLimitDecision(allowed = false, retryAfterSeconds = retryAfter)
        }

        globalTimestamps.addLast(now)
        deque.addLast(now)
        RateLimitDecision(allowed = true, retryAfterSeconds = 0L)
    }

    private fun evictExpired(deque: ArrayDeque<Instant>, cutoff: Instant) {
        while (deque.isNotEmpty() && !deque.peekFirst().isAfter(cutoff)) {
            deque.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_MAX_REQUESTS_PER_CALLER = 10
        const val DEFAULT_MAX_GLOBAL_REQUESTS = 30
        val DEFAULT_WINDOW: Duration = Duration.ofSeconds(60)
        private const val MAX_TRACKED_CALLERS = 512
    }
}

class PlatformCompanyService(
    private val repository: PlatformCompanyRepository,
) {
    suspend fun createCompany(
        principal: GlobalAdminPrincipal,
        command: CreateCompanyCommand,
        correlationId: String?,
    ): PlatformCompanyCreationResult {
        val validated = validateCommand(command)
        val requestHash = canonicalRequestHash(
            slug = validated.slug,
            name = validated.name,
            initialAdminUserId = validated.initialAdminUserId,
        )

        return try {
            repository.createCompanyWithInitialAdmin(
                actorUserId = principal.userId,
                idempotencyKey = validated.idempotencyKey,
                requestHash = requestHash,
                slug = validated.slug,
                name = validated.name,
                initialAdminUserId = validated.initialAdminUserId,
                correlationId = correlationId,
            )
        } catch (conflict: DomainConflictException) {
            recordAuditBestEffort(
                actorUserId = principal.userId,
                action = "platform.company_create_failed",
                targetType = "company",
                targetId = validated.slug,
                outcome = "failed",
                correlationId = correlationId,
                metadataJson = """{"reason":"${escapeJson(conflict.code)}","idempotencyKey":"${escapeJson(validated.idempotencyKey)}"}""",
            )
            throw conflict
        } catch (validation: DomainValidationException) {
            recordAuditBestEffort(
                actorUserId = principal.userId,
                action = "platform.company_create_failed",
                targetType = "company",
                targetId = validated.slug,
                outcome = "failed",
                correlationId = correlationId,
                metadataJson = """{"reason":"validation_failed","idempotencyKey":"${escapeJson(validated.idempotencyKey)}"}""",
            )
            throw validation
        }
    }

    suspend fun recordDenialAudit(
        reasonCode: String,
        correlationId: String?,
    ) {
        recordAuditBestEffort(
            actorUserId = null,
            action = "platform.company_create_denied",
            targetType = "platform_companies",
            targetId = "create",
            outcome = "denied",
            correlationId = correlationId,
            metadataJson = """{"reason":"${escapeJson(reasonCode)}"}""",
        )
    }

    suspend fun recordRateLimitAudit(
        correlationId: String?,
    ) {
        recordAuditBestEffort(
            actorUserId = null,
            action = "platform.company_create_rate_limited",
            targetType = "platform_companies",
            targetId = "create",
            outcome = "denied",
            correlationId = correlationId,
            metadataJson = """{"reason":"rate_limit_exceeded"}""",
        )
    }

    suspend fun recordAuthUnavailableAudit(
        reason: String,
        correlationId: String?,
    ) {
        recordAuditBestEffort(
            actorUserId = null,
            action = "platform.company_create_failed",
            targetType = "platform_companies",
            targetId = "create",
            outcome = "failed",
            correlationId = correlationId,
            metadataJson = """{"reason":"${escapeJson(reason.take(64))}"}""",
        )
    }

    private suspend fun recordAuditBestEffort(
        actorUserId: UUID?,
        action: String,
        targetType: String,
        targetId: String,
        outcome: String,
        correlationId: String?,
        metadataJson: String,
    ) {
        try {
            repository.recordPlatformAuditEvent(
                actorUserId = actorUserId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                outcome = outcome,
                correlationId = correlationId,
                metadataJson = metadataJson,
            )
        } catch (ex: Exception) {
            logger.error(
                "Failed to persist platform audit event [action={}, outcome={}, correlationId={}, exception={}]",
                action,
                outcome,
                correlationId ?: "none",
                ex::class.java.simpleName,
            )
        }
    }

    private data class ValidatedCreateCompany(
        val slug: String,
        val name: String,
        val initialAdminUserId: UUID,
        val idempotencyKey: String,
    )

    private fun validateCommand(command: CreateCompanyCommand): ValidatedCreateCompany {
        val violations = mutableListOf<ValidationViolation>()

        val idempotencyKey = command.idempotencyKey?.trim().orEmpty()
        if (!IDEMPOTENCY_KEY_REGEX.matches(idempotencyKey)) {
            violations.add(
                ValidationViolation(
                    "Idempotency-Key",
                    "must be 1..128 characters using letters, digits, '.', '_', ':', or '-'",
                ),
            )
        }

        val rawSlug = command.slug ?: ""
        val slug = rawSlug.trim()
        if (slug != rawSlug || slug.length !in MIN_SLUG_LENGTH..MAX_SLUG_LENGTH || !SLUG_REGEX.matches(slug)) {
            violations.add(
                ValidationViolation(
                    "slug",
                    "must be 2..63 lowercase alphanumeric characters separated by single hyphens",
                ),
            )
        }

        val name = command.name?.trim().orEmpty()
        if (name.length !in 1..MAX_NAME_LENGTH) {
            violations.add(
                ValidationViolation(
                    "name",
                    "must be between 1 and $MAX_NAME_LENGTH non-blank characters",
                ),
            )
        }

        if (violations.isNotEmpty()) {
            throw DomainValidationException(violations)
        }

        return ValidatedCreateCompany(
            slug = slug,
            name = name,
            initialAdminUserId = command.initialAdminUserId,
            idempotencyKey = idempotencyKey,
        )
    }

    private fun canonicalRequestHash(slug: String, name: String, initialAdminUserId: UUID): String {
        val canonical = "$slug\n$name\n$initialAdminUserId"
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")

    companion object {
        const val MIN_SLUG_LENGTH = 2
        const val MAX_SLUG_LENGTH = 63
        const val MAX_NAME_LENGTH = 120
        val SLUG_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        val IDEMPOTENCY_KEY_REGEX = Regex("^[A-Za-z0-9._:-]{1,128}$")

        private val logger = LoggerFactory.getLogger("com.nexora.backend.application.platform.PlatformCompanyService")
    }
}
