/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LyricsNegativeCacheTest {
    private var now = 0L
    private val cache = NegativeCache(capacity = 2, ttlMs = 1000L, nowMs = { now })

    @Test
    fun `an unrecorded song should be retried`() {
        assertTrue(cache.shouldRetry("never-failed"))
    }

    @Test
    fun `a fresh failure suppresses retry`() {
        cache.recordFailure("song")
        assertFalse(cache.shouldRetry("song"))
    }

    @Test
    fun `a failure older than the ttl is retried`() {
        cache.recordFailure("song")
        now = 1000L
        assertTrue(cache.shouldRetry("song"))
    }

    @Test
    fun `a failure just inside the ttl stays suppressed`() {
        cache.recordFailure("song")
        now = 999L
        assertFalse(cache.shouldRetry("song"))
    }

    @Test
    fun `removing a failure lets the song be retried at once`() {
        cache.recordFailure("song")
        cache.remove("song")
        assertTrue(cache.shouldRetry("song"))
    }

    @Test
    fun `the oldest failure is evicted once capacity is exceeded`() {
        cache.recordFailure("first")
        cache.recordFailure("second")
        cache.recordFailure("third")

        assertTrue("evicted entry must not stay suppressed forever", cache.shouldRetry("first"))
        assertFalse(cache.shouldRetry("second"))
        assertFalse(cache.shouldRetry("third"))
    }

    @Test
    fun `re-recording a failure restarts its ttl`() {
        cache.recordFailure("song")
        now = 900L
        cache.recordFailure("song")
        now = 1500L
        assertFalse(cache.shouldRetry("song"))
    }

    @Test
    fun `the default clock is monotonic rather than wall clock time`() {
        val first = monotonicMs()
        val second = monotonicMs()
        assertTrue("monotonic clock must never go backwards", second >= first)
        assertTrue(
            "expected an uptime-based clock, got something close to epoch millis",
            abs(first - System.currentTimeMillis()) > 60_000L,
        )
    }

    @Test
    fun `capacity of one keeps only the newest failure`() {
        val tiny = NegativeCache(capacity = 1, ttlMs = 1000L, nowMs = { now })
        tiny.recordFailure("old")
        tiny.recordFailure("new")
        assertTrue(tiny.shouldRetry("old"))
        assertFalse(tiny.shouldRetry("new"))
    }
}