/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recognition

import kotlin.math.sqrt

/** -60 dBFS. Real music never sits this low, but a capture-blocked playback stream does. */
const val SILENCE_RMS_THRESHOLD = 0.001

/** Normalised loudness of little-endian 16-bit PCM, in 0..1. A trailing odd byte is ignored. */
fun rmsLevel(
    pcm: ByteArray,
    length: Int,
): Double {
    val sampleCount = length / 2
    if (sampleCount == 0) return 0.0
    var sumSquares = 0.0
    var i = 0
    while (i + 1 < length) {
        val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
        sumSquares += sample.toDouble() * sample
        i += 2
    }
    return sqrt(sumSquares / sampleCount) / 32768.0
}

fun isSilentPcm(
    pcm: ByteArray,
    length: Int,
): Boolean = rmsLevel(pcm, length) < SILENCE_RMS_THRESHOLD