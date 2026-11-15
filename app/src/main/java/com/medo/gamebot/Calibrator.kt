package com.medo.gamebot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * One-time teach-by-tap: the user touches each button once while the game is visible underneath.
 * We store its position and a picture of it, and Locator finds it again wherever it moves.
 * FRIEND / ENEMY taps teach the colour filter.
 */
class Calibrator(
    private val ctx: Context,
    private val snapshot: () -> Bitmap?,
    private val detector: () -> Detector?,
    private val team: Team,
    private val sw: Int,
    private val sh: Int,
    private val onFinish: () -> Unit
) {
    private class Step(val key: String, val msg: String, val optional: Boolean)

    private val steps = listOf(
        Step("FIRE", "اضغط زر الإطلاق (النار)", false),
        Step("STICK", "اضغط منتصف عصا الحركة (يسار)", false),
        Step("STICK_EDGE", "اضغط حافة عصا الحركة (أقصى مدى)", false),
        Step("JUMP", "اضغط زر القفز", true),
        Step("RELOAD", "اضغط زر إعادة التعبئة", true),
        Step("CROUCH", "اضغط زر الانبطاح/الجلوس", true),
        Step("FRIEND", "لما يظهر لاعب صديق: اضغط عليه", true),
        Step("ENEMY", "لما يظهر عدو: اضغط عليه", true)
    )
    private var idx = 0
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var label: TextView? = null
    private var skip: TextView? = null
    private val saved: MutableMap<String, PointF> = Prefs.buttons(ctx)

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun start() {
        val r = FrameLayout(ctx)
        r.setBackgroundColor(Color.parseColor("#06000000"))
        val banner = LinearLayout(ctx)
        banner.orientation = LinearLayout.HORIZONTAL
        banner.setBackgroundColor(Color.parseColor("#EE000000"))
        banner.gravity = Gravity.CENTER_VERTICAL
        banner.setPadding(dp(10), dp(6), dp(10), dp(6))
        val tv = TextView(ctx)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 15f
        label = tv
        val sk = TextView(ctx)
        sk.text = "  تخطّي  "
        sk.setTextColor(Color.parseColor("#FFC107"))
        sk.textSize = 15f
        sk.setOnClickListener { next() }
        skip = sk
        val dn = TextView(ctx)
        dn.text = "  إنهاء  "
        dn.setTextColor(Color.parseColor("#80CBC4"))
        dn.textSize = 15f
        dn.setOnClickListener { finish() }
        banner.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        banner.addView(sk)
        banner.addView(dn)
        r.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        r.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) handleTap(e.rawX, e.rawY)
            true
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SECURE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        wm.addView(r, lp)
        root = r
        render()
    }

    private fun render() {
        val st = steps[idx]
        label?.text = "(${idx + 1}/${steps.size}) ${st.msg}"
        skip?.visibility = if (st.optional) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun next() {
        idx++
        if (idx >= steps.size) finish() else render()
    }

    private fun toast(s: String) { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() }

    private fun handleTap(rx: Float, ry: Float) {
        val st = steps[idx]
        val frame = snapshot()
        val nx = rx / sw
        val ny = ry / sh
        if (st.key == "FRIEND" || st.key == "ENEMY") {
            val det = detector()
            if (frame == null || det == null) { toast("الكاشف لسه بيجهّز"); return }
            val fx = nx * frame.width
            val fy = ny * frame.height
            val ds = try { det.detect(frame) } catch (e: Throwable) { emptyList() }
            val hit = ds.filter { it.box.contains(fx, fy) }.minByOrNull { it.box.width() * it.box.height() }
                ?: ds.minByOrNull { kotlin.math.abs(it.box.centerX() - fx) + kotlin.math.abs(it.box.centerY() - fy) }
            if (hit == null) { toast("ما لقيتش لاعب عند اللمسة"); return }
            team.add(st.key == "ENEMY", frame, hit.box)
            toast(if (st.key == "ENEMY") "اتعلّم العدو" else "اتعلّم الصديق")
        } else {
            saved[st.key] = PointF(nx, ny)
            if (frame != null && Locator.NAMES.contains(st.key)) saveTemplate(st.key, frame, nx, ny)
            toast("تمام")
        }
        next()
    }

    private fun saveTemplate(key: String, frame: Bitmap, nx: Float, ny: Float) {
        val half = 40
        val cx = (nx * frame.width).toInt()
        val cy = (ny * frame.height).toInt()
        val l = (cx - half).coerceIn(0, frame.width - 2 * half)
        val t = (cy - half).coerceIn(0, frame.height - 2 * half)
        try {
            val crop = Bitmap.createBitmap(frame, l, t, 2 * half, 2 * half)
            FileOutputStream(File(ctx.filesDir, "tpl_$key.png")).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            saved[key] = PointF((l + half).toFloat() / frame.width, (t + half).toFloat() / frame.height)
        } catch (e: Exception) {
            Telemetry.log("template error: ${e.message}")
        }
    }

    private fun finish() {
        Prefs.saveButtons(ctx, saved)
        val r = root
        root = null
        if (r != null) try { wm.removeView(r) } catch (e: Exception) { /* ignore */ }
        onFinish()
    }
}
