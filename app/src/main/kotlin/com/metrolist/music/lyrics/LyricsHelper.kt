/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import android.util.LruCache
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.LyricsProviderOrderKey
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_LYRICS_FETCH_MS = 15000L
private const val PER_PROVIDER_TIMEOUT_MS = 6000L
private const val PROVIDER_PREFERENCE_WINDOW_MS = 700L
private const val NOT_FOUND_RETRY_MS = 5 * 60 * 1000L
private const val NOT_FOUND_CACHE_CAPACITY = 128
private const val PROVIDER_NONE = ""

/**
 * Runs every attempt at once and returns the best result that arrives in time. Index 0 is
 * the user's preferred provider: once anything succeeds, a short window lets a
 * higher-priority answer overtake it. Awaiting in priority order instead meant a hanging
 * preferred provider cost its whole timeout even when another had already answered.
 */
internal suspend fun <T : Any> raceWithPreference(
    attempts: List<suspend () -> T?>,
    perAttemptTimeoutMs: Long,
    preferenceWindowMs: Long,
): T? = coroutineScope {
    if (attempts.isEmpty()) return@coroutineScope null

    val completions = Channel<Pair<Int, T?>>(Channel.UNLIMITED)
    val pending = AtomicInteger(attempts.size)
    val jobs = attempts.mapIndexed { index, attempt ->
        launch {
            val value = try {
                withTimeoutOrNull(perAttemptTimeoutMs) { attempt() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            completions.trySend(index to value)
            if (pending.decrementAndGet() == 0) completions.close()
        }
    }

    var best: Pair<Int, T>? = null
    while (best == null) {
        val (index, value) = completions.receiveCatching().getOrNull() ?: break
        if (value != null) best = index to value
    }

    val first = best
    if (first != null && first.first != 0) {
        var current: Pair<Int, T> = first
        withTimeoutOrNull(preferenceWindowMs) {
            while (true) {
                val (index, value) = completions.receiveCatching().getOrNull()
                    ?: return@withTimeoutOrNull
                if (value != null && index < current.first) {
                    current = index to value
                    if (current.first == 0) return@withTimeoutOrNull
                }
            }
        }
        best = current
    }

    jobs.forEach { it.cancel() }
    completions.close()
    best?.second
}

/**
 * Shares one execution between callers asking for the same key while it is still running.
 * The work lives in [scope] so one caller going away does not cancel it for the others.
 */
internal class SingleFlight<K : Any, V : Any>(
    private val scope: CoroutineScope,
) {
    private val inFlight = ConcurrentHashMap<K, Deferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        inFlight[key]?.let { return it.await() }

        val deferred = scope.async { block() }
        val existing = inFlight.putIfAbsent(key, deferred)
        if (existing != null) {
            deferred.cancel()
            return existing.await()
        }
        deferred.invokeOnCompletion { inFlight.remove(key, deferred) }
        return deferred.await()
    }
}

@Singleton
class LyricsHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val networkConnectivity: NetworkConnectivityObserver,
) {
    val preferred =
        context.dataStore.data
            .map { preferences ->
                resolveLyricsProviders(preferences)
            }.distinctUntilChanged()

    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
    private var currentLyricsJob: Job? = null
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetches = SingleFlight<String, LyricsWithProvider>(fetchScope)
    private val notFound = NegativeCache(NOT_FOUND_CACHE_CAPACITY, NOT_FOUND_RETRY_MS)

    fun clearCache(mediaId: String) {
        cache.remove(mediaId)
        notFound.remove(mediaId)
    }

    suspend fun getSongProvider(mediaId: String): String? =
        context.dataStore.data.first()[androidx.datastore.preferences.core.stringPreferencesKey("lyricsProviderOverride_$mediaId")]

    /** Saves the lyrics provider used only for this song. Pass null to go back to the normal order. */
    suspend fun setSongProvider(mediaId: String, providerName: String?) {
        val key = androidx.datastore.preferences.core.stringPreferencesKey("lyricsProviderOverride_$mediaId")
        context.dataStore.edit { prefs ->
            prefs.remove(key)
            if (providerName != null) prefs[key] = providerName
        }
        clearCache(mediaId)
    }

    /**
     * The service, the lyrics pane and the live lyric line all fetch on the same song change.
     * Sharing the in-flight fetch keeps that from becoming three parallel provider fan-outs.
     */
    suspend fun getLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider =
        fetches.run(mediaMetadata.id) { fetchLyrics(mediaMetadata) }

    /**
     * A failed fetch is persisted as LYRICS_NOT_FOUND so the UI can say so, but it is not
     * final — a transient network failure should not cost the song its lyrics forever.
     */
    fun needsFetch(existing: LyricsEntity?, mediaId: String): Boolean {
        if (existing == null) return true
        if (existing.lyrics != LYRICS_NOT_FOUND) return false
        return notFound.shouldRetry(mediaId)
    }

    private suspend fun fetchLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider {
        val orderedProviders = context.dataStore.data
            .map { preferences -> resolveLyricsProviders(preferences) }
            .first()
        val songProviderName = context.dataStore.data.first()[androidx.datastore.preferences.core.stringPreferencesKey("lyricsProviderOverride_${mediaMetadata.id}")]
        val songProvider = songProviderName?.let { LyricsProviderRegistry.getProviderByName(it) }

        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true
        }

        if (!isNetworkAvailable) {
            return LyricsWithProvider(LYRICS_NOT_FOUND, PROVIDER_NONE)
        }

        val result = withTimeoutOrNull(MAX_LYRICS_FETCH_MS) {
            val cleanedTitle = LyricsUtils.cleanTitleForSearch(mediaMetadata.title)
            val enabledProviders = if (songProvider != null) listOf(songProvider) else orderedProviders.filter { it.isEnabled(context) }

            Timber.tag("LyricsHelper").d("Starting parallel fetch for: $cleanedTitle by ${mediaMetadata.artists.joinToString { it.name }}")
            Timber.tag("LyricsHelper").d("Enabled providers: ${enabledProviders.joinToString { it.name }}")

            if (enabledProviders.isEmpty()) {
                return@withTimeoutOrNull LyricsWithProvider(LYRICS_NOT_FOUND, PROVIDER_NONE)
            }

            val attempts: List<suspend () -> Pair<String, String>?> = enabledProviders.map { provider ->
                suspend {
                    val providerResult = try {
                        provider.getLyrics(
                            context,
                            mediaMetadata.id,
                            cleanedTitle,
                            mediaMetadata.artists.joinToString { it.name },
                            mediaMetadata.duration,
                            mediaMetadata.album?.title,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag("LyricsHelper").w("${provider.name} threw: ${e.message}")
                        null
                    }

                    if (providerResult != null && providerResult.isSuccess) {
                        Timber.tag("LyricsHelper").i("Got lyrics from ${provider.name}")
                        provider.name to LyricsUtils.filterLyricsCreditLines(providerResult.getOrNull()!!)
                    } else {
                        val errorMsg = providerResult?.exceptionOrNull()?.message ?: "no result"
                        Timber.tag("LyricsHelper").w("${provider.name} failed: $errorMsg")
                        null
                    }
                }
            }

            val winner = raceWithPreference(attempts, PER_PROVIDER_TIMEOUT_MS, PROVIDER_PREFERENCE_WINDOW_MS)
            if (winner != null) {
                LyricsWithProvider(winner.second, winner.first)
            } else {
                Timber.tag("LyricsHelper").w("No lyrics found after checking all providers")
                LyricsWithProvider(LYRICS_NOT_FOUND, PROVIDER_NONE)
            }
        }

        val resolved = result ?: LyricsWithProvider(LYRICS_NOT_FOUND, PROVIDER_NONE)
        if (resolved.lyrics == LYRICS_NOT_FOUND) {
            notFound.recordFailure(mediaMetadata.id)
        } else {
            notFound.remove(mediaMetadata.id)
        }
        return resolved
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        album: String? = null,
        callback: (LyricsResult) -> Unit,
    ) {
        currentLyricsJob?.cancel()

        cache.get(mediaId)?.let { results ->
            results.forEach { callback(it) }
            return
        }

        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true
        }

        if (!isNetworkAvailable) return

        val allResult = mutableListOf<LyricsResult>()
        currentLyricsJob = CoroutineScope(SupervisorJob()).launch {
            val cleanedTitle = LyricsUtils.cleanTitleForSearch(songTitle)
            val allProviders = context.dataStore.data
                .map { preferences -> resolveLyricsProviders(preferences) }
                .first()
            val enabledProviders = allProviders.filter { it.isEnabled(context) }

            val otherProviders = enabledProviders.filter { it.name != "LyricsPlus" }
            val lyricsPlusProvider = enabledProviders.find { it.name == "LyricsPlus" }

            val callbackMutex = Any()

            val otherJobs = otherProviders.map { provider ->
                launch {
                    try {
                        provider.getAllLyrics(context, mediaId, cleanedTitle, songArtists, duration, album) { lyrics ->
                            val filteredLyrics = LyricsUtils.filterLyricsCreditLines(lyrics)
                            val result = LyricsResult(provider.name, filteredLyrics)
                            synchronized(callbackMutex) {
                                allResult += result
                                callback(result)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }
            }
            otherJobs.forEach { it.join() }

            val otherLyricsCount = allResult.count { it.providerName != "LyricsPlus" }
            if (lyricsPlusProvider != null && otherLyricsCount <= 2) {
                launch {
                    try {
                        lyricsPlusProvider.getAllLyrics(context, mediaId, cleanedTitle, songArtists, duration, album) { lyrics ->
                            val filteredLyrics = LyricsUtils.filterLyricsCreditLines(lyrics)
                            val result = LyricsResult(lyricsPlusProvider.name, filteredLyrics)
                            synchronized(callbackMutex) {
                                allResult += result
                                callback(result)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }.join()
            }

            cache.put(mediaId, allResult)
        }

        currentLyricsJob?.join()
    }

    private fun resolveLyricsProviders(preferences: androidx.datastore.preferences.core.Preferences): List<LyricsProvider> {
        val providerOrder = preferences[LyricsProviderOrderKey].orEmpty()
        if (providerOrder.isNotBlank()) {
            return LyricsProviderRegistry.getOrderedProviders(providerOrder)
        }

        return LyricsProviderRegistry.getDefaultProviderOrder()
            .mapNotNull { LyricsProviderRegistry.getProviderByName(it) }
    }

    companion object {
        private const val MAX_CACHE_SIZE = 3
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

data class LyricsWithProvider(
    val lyrics: String,
    val provider: String,
)
