package com.nexora.backend.adapter.http.ktor

import java.net.InetAddress

/**
 * Resolves the effective client source IP for rate limiting without indiscriminately
 * trusting client-supplied forwarding headers (`X-Forwarded-For` / `Forwarded`).
 *
 * Security model:
 * - Default (`trustedProxies = emptySet()`): ignores ALL forwarding headers and always
 *   returns the normalized direct TCP peer address (`peerAddress`).
 * - When `trustedProxies` is explicitly configured and `peerAddress` is in `trustedProxies`:
 *   walks the forwarding chain from RIGHT to LEFT, skipping known trusted proxy hops, and
 *   returns the rightmost untrusted IP literal. Any client-prepended spoofed entries on the
 *   left are ignored.
 * - Rejects wildcards, hostnames, and `0.0.0.0` / `::` in `trustedProxies`.
 */
class ClientIpResolver(
    trustedProxies: Set<String> = emptySet(),
) {
    private val normalizedTrustedProxies: Set<String> = trustedProxies.map { parseAndValidateProxyIp(it) }.toSet()

    fun resolve(
        peerAddress: String?,
        xForwardedForHeaders: List<String> = emptyList(),
        forwardedHeaders: List<String> = emptyList(),
    ): String {
        val peer = normalizeIpOrFallback(peerAddress)
        if (normalizedTrustedProxies.isEmpty() || peer !in normalizedTrustedProxies) {
            return peer
        }

        val xffHops = parseXForwardedFor(xForwardedForHeaders)
        if (xffHops.isNotEmpty()) {
            return rightmostUntrusted(xffHops, fallback = peer)
        }

        val rfcHops = parseRfc7239Forwarded(forwardedHeaders)
        if (rfcHops.isNotEmpty()) {
            return rightmostUntrusted(rfcHops, fallback = peer)
        }

        return peer
    }

    private fun rightmostUntrusted(hops: List<String>, fallback: String): String {
        for (i in hops.indices.reversed()) {
            val candidate = normalizeLiteralIp(hops[i]) ?: return fallback
            if (candidate !in normalizedTrustedProxies) {
                return candidate
            }
        }
        return fallback
    }

    private fun parseXForwardedFor(headers: List<String>): List<String> =
        headers.flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun parseRfc7239Forwarded(headers: List<String>): List<String> =
        headers.flatMap { it.split(',') }
            .mapNotNull { segment ->
                segment.split(';')
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("for=", ignoreCase = true) }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.let { raw -> stripOptionalPort(raw) }
                    ?.takeIf { it.isNotEmpty() }
            }

    private fun stripOptionalPort(raw: String): String {
        if (raw.startsWith("[")) {
            val endBracket = raw.indexOf(']')
            return if (endBracket > 1) raw.substring(1, endBracket) else raw
        }
        // IPv4:port has a single colon; bare IPv6 has multiple colons
        val firstColon = raw.indexOf(':')
        val lastColon = raw.lastIndexOf(':')
        return if (firstColon != -1 && firstColon == lastColon) raw.substring(0, firstColon) else raw
    }

    private fun normalizeIpOrFallback(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return "unknown-peer"
        return normalizeLiteralIp(trimmed) ?: trimmed.take(64)
    }

    companion object {
        private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val IPV6_LITERAL = Regex("^[0-9a-fA-F:]+$")

        fun parseAndValidateProxyIp(raw: String): String {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty() && trimmed != "*") {
                "NEXORA_TRUSTED_PROXIES entries must be explicit IP literals, not empty or '*'"
            }
            val normalized = normalizeLiteralIp(trimmed)
                ?: throw IllegalArgumentException("Invalid IP literal in NEXORA_TRUSTED_PROXIES: '$trimmed'")
            require(normalized != "0.0.0.0" && normalized != "0:0:0:0:0:0:0:0") {
                "Unspecified address '$trimmed' is not allowed in NEXORA_TRUSTED_PROXIES"
            }
            return normalized
        }

        internal fun normalizeLiteralIp(raw: String): String? {
            val clean = raw.trim().removePrefix("[").removeSuffix("]")
            if (clean.isEmpty() || clean.length > 45) return null
            if (!IPV4_LITERAL.matches(clean) && !(clean.contains(':') && IPV6_LITERAL.matches(clean))) {
                return null
            }
            return try {
                InetAddress.getByName(clean).hostAddress
            } catch (_: Exception) {
                null
            }
        }
    }
}
