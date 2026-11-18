package com.metrolist.music.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A slow drag arrives as fractions of a pixel; rounding each one away would freeze the window. */
class DragAccumulatorTest {
    private val drag = DragAccumulator()

    @Test
    fun `a whole pixel drag moves the window immediately`() {
        assertEquals(3 to -2, drag.step(dx = 3f, dy = -2f))
    }

    @Test
    fun `sub-pixel drags add up instead of being dropped`() {
        assertNull(drag.step(dx = 0.4f, dy = 0f))
        assertNull(drag.step(dx = 0.4f, dy = 0f))
        assertEquals(1 to 0, drag.step(dx = 0.4f, dy = 0f))
    }

    @Test
    fun `the leftover fraction carries into the next drag`() {
        assertNull(drag.step(dx = 0.5f, dy = 0f))
        assertEquals(1 to 0, drag.step(dx = 0.5f, dy = 0f))
        assertNull(drag.step(dx = 0.5f, dy = 0f))
    }

    @Test
    fun `negative sub-pixel drags accumulate too`() {
        assertNull(drag.step(dx = -0.4f, dy = 0f))
        assertNull(drag.step(dx = -0.4f, dy = 0f))
        assertEquals(-1 to 0, drag.step(dx = -0.4f, dy = 0f))
    }

    @Test
    fun `a new gesture drops the previous leftover fraction`() {
        assertNull(drag.step(dx = 0.9f, dy = 0f))
        drag.reset()
        assertNull(drag.step(dx = 0.9f, dy = 0f))
    }

    @Test
    fun `a zero drag reports no move`() {
        assertNull(drag.step(dx = 0f, dy = 0f))
    }
}