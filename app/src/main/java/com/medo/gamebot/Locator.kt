package com.medo.gamebot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

/** Finds the calibrated HUD buttons again wherever they are on screen (template matching). */
class Locator(private val ctx: Context) {
    companion object { val NAMES = listOf("FIRE", "JUMP", "RELOAD", "CROUCH", "STICK") }

    private val tpls = HashMap<String, Mat>()
    private val pos = HashMap<String, PointF>()
    private var ok = false

    init {
        ok = try { OpenCVLoader.initLocal() } catch (t: Throwable) {
            try { System.loadLibrary("opencv_java4"); true } catch (t2: Throwable) { false }
        }
        Telemetry.log("opencv ok=$ok")
        reload()
    }

    @Synchronized
    fun reload() {
        for (m in tpls.values) m.release()
        tpls.clear(); pos.clear()
        if (!ok) return
        for (n in NAMES) {
            val f = File(ctx.filesDir, "tpl_$n.png")
            if (!f.exists()) continue
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            val rgba = Mat(); Utils.bitmapToMat(bmp, rgba)
            val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val small = Mat(); Imgproc.resize(gray, small, Size(), 0.5, 0.5, Imgproc.INTER_AREA)
            rgba.release(); gray.release()
            tpls[n] = small
        }
    }

    @Synchronized
    fun update(frame: Bitmap) {
        if (!ok || tpls.isEmpty()) return
        val rgba = Mat(); Utils.bitmapToMat(frame, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val small = Mat(); Imgproc.resize(gray, small, Size(), 0.5, 0.5, Imgproc.INTER_AREA)
        for ((name, t) in tpls) {
            if (small.cols() < t.cols() || small.rows() < t.rows()) continue
            val res = Mat()
            Imgproc.matchTemplate(small, t, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(res)
            if (mm.maxVal >= 0.70) {
                val cx = (mm.maxLoc.x + t.cols() / 2.0) / small.cols()
                val cy = (mm.maxLoc.y + t.rows() / 2.0) / small.rows()
                pos[name] = PointF(cx.toFloat(), cy.toFloat())
            }
            res.release()
        }
        rgba.release(); gray.release(); small.release()
    }

    @Synchronized
    fun position(name: String): PointF? = pos[name]
}
