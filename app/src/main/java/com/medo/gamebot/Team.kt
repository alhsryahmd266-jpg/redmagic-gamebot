package com.medo.gamebot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.min

/** Friend/enemy filter: colour histograms of the upper body, learned from two taps during calibration. */
class Team(private val ctx: Context) {
    private val bins = 16
    private val enemy: MutableList<FloatArray> = Prefs.protos(ctx, true)
    private val friend: MutableList<FloatArray> = Prefs.protos(ctx, false)

    private fun hist(b: Bitmap, r: RectF): FloatArray {
        val h = FloatArray(bins)
        val x0 = (r.left + r.width() * 0.10f).toInt().coerceIn(0, b.width - 1)
        val x1 = (r.right - r.width() * 0.10f).toInt().coerceIn(x0 + 1, b.width)
        val y0 = r.top.toInt().coerceIn(0, b.height - 1)
        val y1 = (r.top + r.height() * 0.45f).toInt().coerceIn(y0 + 1, b.height)
        val hsv = FloatArray(3)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                Color.colorToHSV(b.getPixel(x, y), hsv)
                val w = hsv[1] * hsv[2]
                if (w >= 0.12f) {
                    val bin = (hsv[0] / 360f * bins).toInt().coerceIn(0, bins - 1)
                    h[bin] += w
                }
                x += 2
            }
            y += 2
        }
        val s = h.sum()
        if (s > 0f) for (i in h.indices) h[i] /= s
        return h
    }

    private fun sim(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += min(a[i], b[i])
        return s
    }

    fun add(isEnemy: Boolean, b: Bitmap, box: RectF) {
        val list = if (isEnemy) enemy else friend
        list.add(hist(b, box))
        while (list.size > 8) list.removeAt(0)
        Prefs.saveProtos(ctx, isEnemy, list)
    }

    fun isFriend(b: Bitmap, box: RectF): Boolean {
        if (friend.isEmpty()) return false
        val h = hist(b, box)
        val sf = friend.maxOf { sim(it, h) }
        val se = if (enemy.isEmpty()) 0f else enemy.maxOf { sim(it, h) }
        return sf > se + 0.08f && sf > 0.45f
    }
}
