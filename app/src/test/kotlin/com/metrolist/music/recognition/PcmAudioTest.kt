package com.metrolist.music.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apps that forbid playback capture yield a stream of digital silence rather than an error,
 * so silence has to be told apart from "Shazam found no match".
 */
class PcmAudioTest {
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            out[index * 2] = (sample and 0xFF).toByte()
            out[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `digital silence is reported as silent`() {
        val buffer = pcm(0, 0, 0, 0)

        assertEquals(0.0, rmsLevel(buffer, buffer.size), 0.0)
        assertTrue(isSilentPcm(buffer, buffer.size))
    }

    @Test
    fun `a full scale signal is not silent`() {
        val buffer = pcm(32767, -32768, 32767, -32768)

        assertEquals(1.0, rmsLevel(buffer, buffer.size), 1e-4)
        assertFalse(isSilentPcm(buffer, buffer.size))
    }

    @Test
    fun `a signal below the threshold is silent`() {
        val buffer = pcm(1, -1, 1, -1)

        assertTrue(isSilentPcm(buffer, buffer.size))
    }

    @Test
    fun `a signal above the threshold is not silent`() {
        val buffer = pcm(100, -100, 100, -100)

        assertFalse(isSilentPcm(buffer, buffer.size))
    }

    @Test
    fun `an empty buffer is silent`() {
        assertEquals(0.0, rmsLevel(ByteArray(0), 0), 0.0)
        assertTrue(isSilentPcm(ByteArray(0), 0))
    }

    /** AudioRecord can hand back an odd byte count; the half sample must not be read. */
    @Test
    fun `a trailing odd byte is ignored`() {
        val buffer = pcm(100, 100) + byteArrayOf(0x7F)

        assertEquals(rmsLevel(buffer, 4), rmsLevel(buffer, 5), 0.0)
    }
}