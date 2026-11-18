/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recognition

import android.content.pm.ServiceInfo

enum class RecognitionSource {
    MICROPHONE,
    PLAYBACK,
}

fun foregroundServiceTypeFor(source: RecognitionSource): Int =
    when (source) {
        RecognitionSource.MICROPHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        RecognitionSource.PLAYBACK -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    }