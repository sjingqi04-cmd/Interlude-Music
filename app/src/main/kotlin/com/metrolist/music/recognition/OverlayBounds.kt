/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recognition

data class OverlayArea(
    val width: Int,
    val height: Int,
) {
    fun clamp(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
    ): Pair<Int, Int> =
        x.coerceIn(0, (width - windowWidth).coerceAtLeast(0)) to
            y.coerceIn(0, (height - windowHeight).coerceAtLeast(0))
}

/** Accumulates drag deltas so a slow gesture still moves the window, one whole pixel at a time. */
class DragAccumulator {
    private var remainderX = 0f
    private var remainderY = 0f

    /** Returns the whole-pixel move this delta completes, or null while it is still sub-pixel. */
    fun step(
        dx: Float,
        dy: Float,
    ): Pair<Int, Int>? {
        remainderX += dx
        remainderY += dy
        val moveX = remainderX.toInt()
        val moveY = remainderY.toInt()
        if (moveX == 0 && moveY == 0) return null
        remainderX -= moveX
        remainderY -= moveY
        return moveX to moveY
    }

    fun reset() {
        remainderX = 0f
        remainderY = 0f
    }
}