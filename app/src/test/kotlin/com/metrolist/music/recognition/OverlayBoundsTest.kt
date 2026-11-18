package com.metrolist.music.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

/** A dragged overlay must never end up somewhere the user cannot grab it again. */
class OverlayBoundsTest {
    private val screen = OverlayArea(width = 1080, height = 2400)

    @Test
    fun `a position inside the screen is left alone`() {
        assertEquals(100 to 200, screen.clamp(x = 100, y = 200, windowWidth = 300, windowHeight = 120))
    }

    @Test
    fun `a negative position is pulled back to the top left`() {
        assertEquals(0 to 0, screen.clamp(x = -40, y = -90, windowWidth = 300, windowHeight = 120))
    }

    @Test
    fun `a position past the right edge keeps the window fully on screen`() {
        assertEquals(780 to 200, screen.clamp(x = 1000, y = 200, windowWidth = 300, windowHeight = 120))
    }

    @Test
    fun `a position past the bottom edge keeps the window fully on screen`() {
        assertEquals(100 to 2280, screen.clamp(x = 100, y = 2390, windowWidth = 300, windowHeight = 120))
    }

    @Test
    fun `a window larger than the screen is pinned to the top left`() {
        assertEquals(0 to 0, screen.clamp(x = 500, y = 500, windowWidth = 2000, windowHeight = 3000))
    }
}