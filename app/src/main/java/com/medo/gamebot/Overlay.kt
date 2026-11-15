package com.medo.gamebot

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Floating bubble (start/pause), always-visible red STOP while running, stats line, long-press menu. */
class Overlay(private val ctx: Context, private val cb: Callbacks) {
    interface Callbacks {
        fun onToggle()
        fun onStop()
        fun onCalibrate()
        fun onCopyLog()
        fun onQuit()
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var stopBtn: TextView? = null
    private var stats: TextView? = null
    private var menu: LinearLayout? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, x: Int, y: Int, touchable: Boolean = true): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SECURE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x
        p.y = y
        return p
    }

    private fun circle(text: String, color: Int): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.textSize = 20f
        t.gravity = Gravity.CENTER
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        t.background = g
        return t
    }

    private fun drag(v: View, p: WindowManager.LayoutParams, onClick: () -> Unit, onLong: (() -> Unit)?) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        var down = 0L
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false
                    down = SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        p.x = ox + dx.toInt(); p.y = oy + dy.toInt()
                        try { wm.updateViewLayout(v, p) } catch (ex: Exception) { /* view gone */ }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (onLong != null && SystemClock.uptimeMillis() - down > 600) onLong() else onClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun show() {
        ui.post {
            if (bubble != null) return@post
            val sw = ctx.resources.displayMetrics.widthPixels
            val b = circle("▶", Color.parseColor("#CC546E7A"))
            val bp = lp(dp(56), dp(56), (sw * 0.30f).toInt(), dp(6))
            drag(b, bp, { cb.onToggle() }, { showMenu(bp) })
            wm.addView(b, bp)
            bubble = b

            val s = circle("■", Color.parseColor("#E5D32F2F"))
            s.textSize = 18f
            val sp = lp(dp(48), dp(48), bp.x + dp(64), dp(10))
            drag(s, sp, { cb.onStop() }, null)
            s.visibility = View.GONE
            wm.addView(s, sp)
            stopBtn = s

            val st = TextView(ctx)
            st.setTextColor(Color.WHITE)
            st.textSize = 10f
            st.setBackgroundColor(Color.parseColor("#99000000"))
            st.setPadding(dp(6), dp(2), dp(6), dp(2))
            wm.addView(st, lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, dp(6), dp(72), false))
            stats = st
        }
    }

    private fun showMenu(anchor: WindowManager.LayoutParams) {
        hideMenu()
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setBackgroundColor(Color.parseColor("#EE202020"))
        row.setPadding(dp(6), dp(6), dp(6), dp(6))
        fun item(t: String, f: () -> Unit) {
            val v = TextView(ctx)
            v.text = t
            v.setTextColor(Color.WHITE)
            v.textSize = 14f
            v.setPadding(dp(10), dp(8), dp(10), dp(8))
            v.setOnClickListener { hideMenu(); f() }
            row.addView(v)
        }
        item("⚙ معايرة") { cb.onCalibrate() }
        item("📋 نسخ السجل") { cb.onCopyLog() }
        item("✖ إغلاق") { cb.onQuit() }
        wm.addView(row, lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, anchor.x, anchor.y + dp(60)))
        menu = row
        ui.postDelayed({ hideMenu() }, 6000)
    }

    private fun hideMenu() {
        val m = menu ?: return
        menu = null
        try { wm.removeView(m) } catch (e: Exception) { /* already gone */ }
    }

    fun setRunning(r: Boolean) {
        ui.post {
            val b = bubble ?: return@post
            b.text = if (r) "⏸" else "▶"
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(Color.parseColor(if (r) "#CC2E7D32" else "#CC546E7A"))
            b.background = g
            stopBtn?.visibility = if (r) View.VISIBLE else View.GONE
        }
    }

    fun setStats(s: String) { ui.post { stats?.text = s } }

    fun hide() {
        ui.post {
            hideMenu()
            for (v in listOf<View?>(bubble, stopBtn, stats)) {
                if (v != null) try { wm.removeView(v) } catch (e: Exception) { /* ignore */ }
            }
            bubble = null; stopBtn = null; stats = null
        }
    }
}
