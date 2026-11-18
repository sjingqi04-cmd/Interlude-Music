package com.metrolist.music.extensions

import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification has room for two custom buttons beside play/pause, so shuffle and repeat
 * share one button that walks four modes instead of toggling two independent flags.
 */
class PlaybackModeTest {
    private val playInOrder = REPEAT_MODE_OFF to false
    private val repeatQueue = REPEAT_MODE_ALL to false
    private val repeatCurrentSong = REPEAT_MODE_ONE to false
    private val shuffle = REPEAT_MODE_OFF to true

    @Test
    fun `play in order advances to repeat queue`() {
        assertEquals(repeatQueue, nextPlaybackMode(REPEAT_MODE_OFF, shuffleEnabled = false))
    }

    @Test
    fun `repeat queue advances to repeat current song`() {
        assertEquals(repeatCurrentSong, nextPlaybackMode(REPEAT_MODE_ALL, shuffleEnabled = false))
    }

    @Test
    fun `repeat current song advances to shuffle`() {
        assertEquals(shuffle, nextPlaybackMode(REPEAT_MODE_ONE, shuffleEnabled = false))
    }

    @Test
    fun `shuffle advances back to play in order`() {
        assertEquals(playInOrder, nextPlaybackMode(REPEAT_MODE_OFF, shuffleEnabled = true))
    }

    /** The in-app player can still leave repeat on while shuffling; the cycle has to absorb that. */
    @Test
    fun `leaving shuffle clears a repeat mode that was left on`() {
        assertEquals(playInOrder, nextPlaybackMode(REPEAT_MODE_ALL, shuffleEnabled = true))
        assertEquals(playInOrder, nextPlaybackMode(REPEAT_MODE_ONE, shuffleEnabled = true))
    }

    @Test
    fun `four presses return to play in order`() {
        var mode = playInOrder
        repeat(4) { mode = nextPlaybackMode(mode.first, mode.second) }

        assertEquals(playInOrder, mode)
    }

    @Test
    fun `the cycle never invents a mode outside the four`() {
        val allowed = setOf(playInOrder, repeatQueue, repeatCurrentSong, shuffle)

        for (repeatMode in listOf(REPEAT_MODE_OFF, REPEAT_MODE_ALL, REPEAT_MODE_ONE)) {
            for (shuffleEnabled in listOf(false, true)) {
                val next = nextPlaybackMode(repeatMode, shuffleEnabled)
                assertTrue("($repeatMode, $shuffleEnabled) produced $next", next in allowed)
            }
        }
    }
}