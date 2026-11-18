/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

/** Uptime-based, so an NTP correction or a manual clock change cannot distort a TTL. */
internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

/**
 * Remembers which songs recently came back empty so a transient failure does not cost them
 * their lyrics forever. Bounded because a long listening session touches many songs.
 */
internal class NegativeCache(
    private val capacity: Int,
    private val ttlMs: Long,
    private val nowMs: () -> Long = ::monotonicMs,
) {
    private val failedAt = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > capacity
    }

    fun recordFailure(key: String) {
        synchronized(failedAt) { failedAt[key] = nowMs() }
    }

    fun remove(key: String) {
        synchronized(failedAt) { failedAt.remove(key) }
    }

    fun shouldRetry(key: String): Boolean {
        val at = synchronized(failedAt) { failedAt[key] } ?: return true
        return nowMs() - at >= ttlMs
    }
}