package com.medo.gamebot

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/** Screen-space geometry of the calibrated controls (all in real screen pixels). */
data class Layout(
    val sw: Int,
    val sh: Int,
    val stick: PointF,
    val stickR: Float,
    val fire: PointF,
    val jump: PointF?,
    val reload: PointF?,
    val crouch: PointF?,
    val lookC: PointF,
    val lookHalf: Float,
    val capToScreen: Float,
    val gain: Float
) {
    fun point(name: String): PointF? = when (name) {
        "JUMP" -> jump
        "RELOAD" -> reload
        "CROUCH" -> crouch
        "FIRE" -> fire
        else -> null
    }
}

/**
 * Turns Brain commands into touches. Move stick, look-drag and fire-hold are three parallel
 * "continuing" strokes re-issued every tick, so they behave like fingers that stay on the screen.
 */
class Driver {
    @Volatile var cmd: Brain.Cmd = Brain.Cmd()
    @Volatile var enabled = false
    @Volatile var layoutProvider: () -> Layout? = { null }
    val taps = ConcurrentLinkedQueue<String>()

    @Volatile private var running = false
    private var thread: Thread? = null
    private var moveS: GestureDescription.StrokeDescription? = null
    private var moveP: PointF? = null
    private var lookS: GestureDescription.StrokeDescription? = null
    private var lookP: PointF? = null
    private var lookReset = false
    private var fireS: GestureDescription.StrokeDescription? = null
    private var firePt: PointF? = null
    private val tick = 80L

    fun start() {
        if (thread != null) return
        running = true
        val t = Thread { loop() }
        t.name = "gb-driver"
        t.start()
        thread = t
    }

    fun stop() {
        running = false
        enabled = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running) {
            try {
                val svc = GameA11yService.instance
                val lay = layoutProvider()
                if (svc == null || lay == null) { Thread.sleep(100); continue }
                val g = build(cmd, lay, enabled)
                if (g == null) { Thread.sleep(30); continue }
                if (svc.dispatch(g, 500)) Telemetry.gOk++ else { Telemetry.gBad++; reset() }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Telemetry.log("driver: ${e.message}")
                reset()
                try { Thread.sleep(50) } catch (ie: InterruptedException) { break }
            }
        }
    }

    private fun reset() {
        moveS = null; moveP = null; lookS = null; lookP = null; lookReset = false; fireS = null; firePt = null
    }

    private fun cont(prev: GestureDescription.StrokeDescription?, path: Path, ms: Long, willContinue: Boolean): GestureDescription.StrokeDescription =
        if (prev == null) GestureDescription.StrokeDescription(path, 0L, ms, willContinue)
        else prev.continueStroke(path, 0L, ms, willContinue)

    private fun lift(prev: GestureDescription.StrokeDescription, at: PointF): GestureDescription.StrokeDescription {
        val path = Path()
        path.moveTo(at.x, at.y)
        return prev.continueStroke(path, 0L, 10L, false)
    }

    private fun build(c: Brain.Cmd, l: Layout, on: Boolean): GestureDescription? {
        val b = GestureDescription.Builder()
        var n = 0

        // MOVE (virtual stick)
        if (on && (abs(c.mx) > 0.05f || abs(c.my) > 0.05f)) {
            val tgt = PointF(l.stick.x + c.mx * l.stickR, l.stick.y - c.my * l.stickR)
            val from = moveP ?: PointF(l.stick.x, l.stick.y)
            val path = Path()
            path.moveTo(from.x, from.y); path.lineTo(tgt.x, tgt.y)
            val s = cont(moveS, path, tick, true)
            moveS = s; moveP = tgt
            b.addStroke(s); n++
        } else {
            val s = moveS
            val at = moveP
            if (s != null && at != null) { b.addStroke(lift(s, at)); n++ }
            moveS = null; moveP = null
        }

        // LOOK (drag inside a free region of the right side)
        val wantLook = on && (abs(c.lx) > 0.5f || abs(c.ly) > 0.5f)
        if (lookReset) {
            val s = lookS
            val at = lookP
            if (s != null && at != null) { b.addStroke(lift(s, at)); n++ }
            lookS = null; lookP = null; lookReset = false
        } else if (wantLook) {
            val from = lookP ?: PointF(l.lookC.x, l.lookC.y)
            val k = l.capToScreen * l.gain
            val tx = (from.x + c.lx * k).coerceIn(l.lookC.x - l.lookHalf, l.lookC.x + l.lookHalf)
            val ty = (from.y + c.ly * k).coerceIn(l.lookC.y - l.lookHalf, l.lookC.y + l.lookHalf)
            val path = Path()
            path.moveTo(from.x, from.y); path.lineTo(tx, ty)
            val s = cont(lookS, path, tick, true)
            lookS = s; lookP = PointF(tx, ty)
            b.addStroke(s); n++
            if (abs(tx - l.lookC.x) >= l.lookHalf - 1f || abs(ty - l.lookC.y) >= l.lookHalf - 1f) lookReset = true
        } else {
            val s = lookS
            val at = lookP
            if (s != null && at != null) { b.addStroke(lift(s, at)); n++ }
            lookS = null; lookP = null
        }

        // FIRE (hold)
        if (on && c.fire) {
            val p = firePt ?: PointF(l.fire.x, l.fire.y)
            val path = Path()
            path.moveTo(p.x, p.y)
            val s = cont(fireS, path, tick, true)
            fireS = s; firePt = p
            b.addStroke(s); n++
        } else {
            val s = fireS
            val at = firePt
            if (s != null && at != null) { b.addStroke(lift(s, at)); n++ }
            fireS = null; firePt = null
        }

        // one-shot taps: jump / reload / crouch
        if (on) {
            var k = 0
            while (k < 2) {
                val name = taps.poll() ?: break
                val pt = l.point(name)
                if (pt != null) {
                    val path = Path()
                    path.moveTo(pt.x, pt.y)
                    b.addStroke(GestureDescription.StrokeDescription(path, 0L, 45L))
                    n++
                }
                k++
            }
        } else taps.clear()

        return if (n == 0) null else b.build()
    }
}
