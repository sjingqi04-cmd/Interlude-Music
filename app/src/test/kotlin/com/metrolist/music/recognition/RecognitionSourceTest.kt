package com.metrolist.music.recognition

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android 14+ requires the foreground service type to match what is actually being accessed,
 * and a media projection may only be used after the service is foreground with that type.
 */
class RecognitionSourceTest {
    @Test
    fun `microphone capture declares the microphone type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            foregroundServiceTypeFor(RecognitionSource.MICROPHONE),
        )
    }

    /** Playback capture never opens the microphone, so claiming that type would over-declare. */
    @Test
    fun `playback capture declares the media projection type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            foregroundServiceTypeFor(RecognitionSource.PLAYBACK),
        )
    }
}