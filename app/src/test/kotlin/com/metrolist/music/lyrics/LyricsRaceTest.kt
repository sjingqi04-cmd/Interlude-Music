package com.metrolist.music.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opening lyrics used to await providers in the user's priority order, so a preferred
 * provider that hung cost its whole timeout even when another had already answered.
 */
class LyricsRaceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `a hanging preferred provider does not block a fast fallback`() = runBlocking {
        val started = System.currentTimeMillis()
        val result = raceWithPreference(
            attempts = listOf(
                { delay(5_000); "preferred" },
                { "fallback" },
            ),
            perAttemptTimeoutMs = 3_000,
            preferenceWindowMs = 50,
        )
        val elapsed = System.currentTimeMillis() - started

        assertEquals("fallback", result)
        assertTrue("waited ${elapsed}ms for a provider that never answers", elapsed < 1_000)
    }

    @Test
    fun `the preferred provider wins when it answers inside the preference window`() = runBlocking {
        val result = raceWithPreference(
            attempts = listOf(
                { delay(60); "preferred" },
                { "fallback" },
            ),
            perAttemptTimeoutMs = 3_000,
            preferenceWindowMs = 400,
        )

        assertEquals("preferred", result)
    }

    @Test
    fun `a successful preferred provider returns without waiting out the window`() = runBlocking {
        val started = System.currentTimeMillis()
        val result = raceWithPreference(
            attempts = listOf(
                { "preferred" },
                { delay(2_000); "fallback" },
            ),
            perAttemptTimeoutMs = 3_000,
            preferenceWindowMs = 5_000,
        )
        val elapsed = System.currentTimeMillis() - started

        assertEquals("preferred", result)
        assertTrue("sat through the preference window for nothing (${elapsed}ms)", elapsed < 1_000)
    }

    @Test
    fun `the preference window is a fixed deadline rather than restarting on each arrival`() = runBlocking {
        val started = System.currentTimeMillis()
        val result = raceWithPreference(
            attempts = listOf(
                { delay(10_000); "preferred" },
                { "second" },
                { delay(50); "third" },
                { delay(100); "fourth" },
                { delay(150); "fifth" },
                { delay(200); "sixth" },
            ),
            perAttemptTimeoutMs = 10_000,
            preferenceWindowMs = 100,
        )
        val elapsed = System.currentTimeMillis() - started

        assertEquals("second", result)
        assertTrue("window kept restarting, took ${elapsed}ms", elapsed < 300)
    }

    @Test
    fun `a provider exceeding its own deadline counts as a failure`() = runBlocking {
        val result = raceWithPreference(
            attempts = listOf({ delay(5_000); "too slow" }),
            perAttemptTimeoutMs = 80,
            preferenceWindowMs = 50,
        )

        assertEquals(null, result)
    }

    @Test
    fun `a provider that throws does not take down the others`() = runBlocking {
        val result = raceWithPreference(
            attempts = listOf(
                { error("provider exploded") },
                { delay(30); "survivor" },
            ),
            perAttemptTimeoutMs = 3_000,
            preferenceWindowMs = 200,
        )

        assertEquals("survivor", result)
    }

    @Test
    fun `every provider failing yields null instead of hanging`() = runBlocking {
        val result = raceWithPreference(
            attempts = listOf(
                { null },
                { error("dead") },
                { delay(5_000); "never" },
            ),
            perAttemptTimeoutMs = 80,
            preferenceWindowMs = 50,
        )

        assertEquals(null, result)
    }

    @Test
    fun `no providers yields null`() = runBlocking {
        assertEquals(null, raceWithPreference<String>(emptyList(), 1_000, 100))
    }

    @Test
    fun `losers are cancelled once a winner is chosen`() = runBlocking {
        val stillRunning = AtomicInteger()
        val result = raceWithPreference(
            attempts = listOf(
                { "preferred" },
                {
                    stillRunning.incrementAndGet()
                    try {
                        delay(10_000)
                        "late"
                    } finally {
                        stillRunning.decrementAndGet()
                    }
                },
            ),
            perAttemptTimeoutMs = 10_000,
            preferenceWindowMs = 50,
        )
        delay(150)

        assertEquals("preferred", result)
        assertEquals("losing provider was left running", 0, stillRunning.get())
    }

    @Test
    fun `concurrent callers asking for the same song share one fetch`() = runBlocking {
        val flight = SingleFlight<String, String>(scope)
        val fetches = AtomicInteger()

        val results = (1..3).map {
            async {
                flight.run("song-a") {
                    delay(80)
                    fetches.incrementAndGet()
                    "lyrics"
                }
            }
        }.map { it.await() }

        assertEquals(listOf("lyrics", "lyrics", "lyrics"), results)
        assertEquals("three callers triggered three network fetches", 1, fetches.get())
    }

    @Test
    fun `different songs are fetched independently`() = runBlocking {
        val flight = SingleFlight<String, String>(scope)
        val fetches = AtomicInteger()

        val first = async { flight.run("song-a") { delay(50); fetches.incrementAndGet(); "A" } }
        val second = async { flight.run("song-b") { delay(50); fetches.incrementAndGet(); "B" } }

        assertEquals("A", first.await())
        assertEquals("B", second.await())
        assertEquals(2, fetches.get())
    }

    @Test
    fun `a finished song is fetched again rather than served from the dedup map`() = runBlocking {
        val flight = SingleFlight<String, String>(scope)
        val fetches = AtomicInteger()

        repeat(2) {
            assertEquals("lyrics", flight.run("song-a") { fetches.incrementAndGet(); "lyrics" })
        }

        assertEquals(2, fetches.get())
    }

    @Test
    fun `a failed fetch is reported to every waiter and does not poison the song`() = runBlocking {
        val flight = SingleFlight<String, String>(scope)
        val fetches = AtomicInteger()

        val outcomes = (1..2).map {
            async {
                runCatching {
                    flight.run("song-a") {
                        delay(60)
                        fetches.incrementAndGet()
                        error("network down")
                    }
                }
            }
        }.map { it.await() }

        assertTrue("a waiter silently got a value", outcomes.all { it.isFailure })
        assertEquals(1, fetches.get())
        assertEquals("retry after failure was blocked", "recovered", flight.run("song-a") { "recovered" })
    }
}