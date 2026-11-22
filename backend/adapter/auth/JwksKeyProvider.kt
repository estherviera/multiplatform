package com.nexora.backend.adapter.auth

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class JwksUnavailableException(
    val reason: String,
    cause: Throwable? = null,
) : RuntimeException(reason, cause)

fun interface JwksFetcher {
    fun fetch(): String
}

interface JwksKeyProvider {
    fun findKey(kid: String): JWK?
    fun invalidateCache()
}

class HttpJwksFetcher(
    private val jwksUri: URI,
    private val timeout: Duration = Duration.ofMillis(JwtAuthConfig.DEFAULT_JWKS_TIMEOUT_MS),
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : JwksFetcher {
    override fun fetch(): String {
        val request = HttpRequest.newBuilder(jwksUri)
            .GET()
            .timeout(timeout)
            .header("Accept", "application/json")
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw JwksUnavailableException("jwks_fetch_interrupted", ex)
        } catch (ex: Exception) {
            throw JwksUnavailableException("jwks_fetch_failed", ex)
        }

        if (response.statusCode() != 200) {
            response.body().use { /* drain/close */ }
            throw JwksUnavailableException("jwks_http_status_${response.statusCode()}")
        }

        val bytes = try {
            response.body().use { readBounded(it, maxResponseBytes) }
        } catch (ex: JwksUnavailableException) {
            throw ex
        } catch (ex: Exception) {
            throw JwksUnavailableException("jwks_read_failed", ex)
        }

        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun readBounded(stream: InputStream, limit: Int): ByteArray {
        val data = stream.readNBytes(limit + 1)
        if (data.size > limit) {
            throw JwksUnavailableException("jwks_response_too_large")
        }
        return data
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 262_144
    }
}

class CachingJwksKeyProvider(
    private val fetcher: JwksFetcher,
    private val cacheTtl: Duration = Duration.ofSeconds(JwtAuthConfig.DEFAULT_JWKS_CACHE_TTL_SECONDS),
    private val minRefreshInterval: Duration = Duration.ofSeconds(JwtAuthConfig.DEFAULT_JWKS_MIN_REFRESH_SECONDS),
    private val maxKeys: Int = JwtAuthConfig.DEFAULT_MAX_CACHED_KEYS,
    private val clock: Clock = Clock.systemUTC(),
) : JwksKeyProvider {
    private data class CachedKeys(
        val keysById: Map<String, JWK>,
        val expiresAt: Instant,
    )

    private val lock = ReentrantLock()

    @Volatile
    private var cached: CachedKeys? = null

    @Volatile
    private var lastFetchAttempt: Instant? = null

    override fun findKey(kid: String): JWK? {
        val now = clock.instant()
        val current = cached
        if (current != null && now.isBefore(current.expiresAt)) {
            current.keysById[kid]?.let { return it }
        }

        return lock.withLock {
            val lockedNow = clock.instant()
            val lockedCache = cached
            val isCacheFresh = lockedCache != null && lockedNow.isBefore(lockedCache.expiresAt)
            if (isCacheFresh) {
                lockedCache.keysById[kid]?.let { return it }
                if (!canRefreshForUnknownKey(lockedNow)) {
                    return null
                }
            }

            val refreshed = refreshLocked(lockedNow)
            refreshed.keysById[kid]
        }
    }

    override fun invalidateCache() {
        lock.withLock {
            cached = null
            lastFetchAttempt = null
        }
    }

    private fun canRefreshForUnknownKey(now: Instant): Boolean {
        val last = lastFetchAttempt ?: return true
        return !now.isBefore(last.plus(minRefreshInterval))
    }

    private fun refreshLocked(now: Instant): CachedKeys {
        lastFetchAttempt = now
        val raw = fetcher.fetch()
        val jwkSet = try {
            JWKSet.parse(raw)
        } catch (ex: ParseException) {
            throw JwksUnavailableException("jwks_parse_failed", ex)
        }

        val allKeys = jwkSet.keys
        if (allKeys.size > maxKeys) {
            throw JwksUnavailableException("jwks_key_limit_exceeded")
        }

        val map = linkedMapOf<String, JWK>()
        for (jwk in allKeys) {
            val id = jwk.keyID?.trim()
            if (!id.isNullOrEmpty() && !jwk.isPrivate) {
                map[id] = jwk
            }
        }

        val updated = CachedKeys(
            keysById = map.toMap(),
            expiresAt = now.plus(cacheTtl),
        )
        cached = updated
        return updated
    }
}
