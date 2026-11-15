package com.medo.gamebot

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

/** Cheap frame signals: "I am being hit" (red screen edges) and "I am stuck" (no motion while walking). */
class Signals {
    private var prev: IntArray? = null
    private var baseRed = 0f
    private var stillSince = 0L

    fun update(b: Bitmap, now: Long, walking: Boolean): Pair<Boolean, Boolean> {
        val gx = 48
        val gy = 24
        val cur = IntArray(gx * gy)
        var red = 0f
        var nb = 0
        for (j in 0 until gy) {
            for (i in 0 until gx) {
                val x = ((i + 0.5f) / gx * b.width).toInt().coerceIn(0, b.width - 1)
                val y = ((j + 0.5f) / gy * b.height).toInt().coerceIn(0, b.height - 1)
                val px = b.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val bl = px and 0xFF
                cur[j * gx + i] = (r * 30 + g * 59 + bl * 11) / 100
                if (i < 4 || i >= gx - 4 || j < 3 || j >= gy - 3) { red += r - (g + bl) / 2f; nb++ }
            }
        }
        val edgeRed = red / max(1, nb)
        baseRed = if (baseRed == 0f) edgeRed else baseRed * 0.97f + edgeRed * 0.03f
        val hit = edgeRed - baseRed > 22f
        var diff = 0f
        val p = prev
        if (p != null && p.size == cur.size) {
            for (k in cur.indices) diff += abs(cur[k] - p[k])
            diff /= cur.size
        }
        prev = cur
        val still = diff < 1.4f
        if (!walking || !still) stillSince = now
        return Pair(hit, walking && still && now - stillSince > 700)
    }
}
