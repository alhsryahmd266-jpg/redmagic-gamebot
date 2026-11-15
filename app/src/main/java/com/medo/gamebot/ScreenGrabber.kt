package com.medo.gamebot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display

/** Real-time screen capture (MediaProjection) scaled to 960px on the long side; calls onFrame on its own thread. */
class ScreenGrabber(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val onFrame: (Bitmap) -> Unit
) {
    private val thread = HandlerThread("gb-capture").apply { start() }
    val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var padded: Bitmap? = null
    private var landscape = true
    private var lastPoll = 0L
    private val lock = Any()
    private var last: Bitmap? = null

    @Volatile var capW = 0
        private set
    @Volatile var capH = 0
        private set
    @Volatile var realW = 0
        private set
    @Volatile var realH = 0
        private set

    private fun realSize(): Pair<Int, Int> {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        return Pair(m.widthPixels, m.heightPixels)
    }

    fun start() { handler.post { setup() } }

    private fun setup() {
        val (rw, rh) = realSize()
        realW = rw; realH = rh
        val longSide = 960
        val shortSide = ((minOf(rw, rh) * (longSide.toFloat() / maxOf(rw, rh))).toInt() / 2) * 2
        landscape = rw >= rh
        val w = if (landscape) longSide else shortSide
        val h = if (landscape) shortSide else longSide
        capW = w; capH = h
        reader?.close(); vd?.release()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        vd = projection.createVirtualDisplay(
            "gamebot", w, h, ctx.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        r.setOnImageAvailableListener({ rd -> handleImage(rd) }, handler)
        padded = null
        Telemetry.log("capture ${w}x$h real ${rw}x$rh")
    }

    private fun handleImage(rd: ImageReader) {
        val now = System.currentTimeMillis()
        if (now - lastPoll > 1000) {
            lastPoll = now
            val (rw, rh) = realSize()
            if ((rw >= rh) != landscape || rw != realW) {
                rd.acquireLatestImage()?.close()
                setup()
                return
            }
        }
        val img = rd.acquireLatestImage() ?: return
        try {
            val plane = img.planes[0]
            val bw = plane.rowStride / plane.pixelStride
            var pb = padded
            if (pb == null || pb.width != bw || pb.height != capH) {
                pb = Bitmap.createBitmap(bw, capH, Bitmap.Config.ARGB_8888)
                padded = pb
            }
            synchronized(lock) {
                plane.buffer.rewind()
                pb.copyPixelsFromBuffer(plane.buffer)
            }
            val frame = if (bw == capW) pb else Bitmap.createBitmap(pb, 0, 0, capW, capH)
            last = frame
            onFrame(frame)
        } catch (e: Exception) {
            Telemetry.log("frame error: ${e.message}")
        } finally {
            img.close()
        }
    }

    fun snapshot(): Bitmap? = synchronized(lock) { last?.copy(Bitmap.Config.ARGB_8888, false) }

    fun stop() {
        try { reader?.setOnImageAvailableListener(null, null) } catch (e: Exception) { /* ignore */ }
        try { vd?.release() } catch (e: Exception) { /* ignore */ }
        try { reader?.close() } catch (e: Exception) { /* ignore */ }
        thread.quitSafely()
    }
}
