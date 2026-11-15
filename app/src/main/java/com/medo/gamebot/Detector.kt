package com.medo.gamebot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * On-device neural detector running on the GPU when possible.
 * Uses assets/enemy_detector.tflite (a model trained on shooter data) when present,
 * otherwise the generic EfficientDet-Lite0 "person" detector.
 */
class Detector(ctx: Context) {
    data class Det(val box: RectF, val score: Float, val label: String)

    private val det: ObjectDetector
    val usingGpu: Boolean
    val custom: Boolean

    init {
        val hasCustom = try { ctx.assets.list("")?.contains("enemy_detector.tflite") == true } catch (e: Exception) { false }
        custom = hasCustom
        val model = if (hasCustom) "enemy_detector.tflite" else "efficientdet_lite0.tflite"
        fun build(delegate: Delegate): ObjectDetector {
            val base = BaseOptions.builder().setModelAssetPath(model).setDelegate(delegate).build()
            val ob = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(0.30f)
                .setMaxResults(8)
            if (!hasCustom) ob.setCategoryAllowlist(listOf("person"))
            return ObjectDetector.createFromOptions(ctx, ob.build())
        }
        var gpu = true
        det = try { build(Delegate.GPU) } catch (e: Throwable) { gpu = false; build(Delegate.CPU) }
        usingGpu = gpu
        Telemetry.log("detector model=$model gpu=$gpu")
    }

    fun detect(bmp: Bitmap): List<Det> {
        val res = det.detect(BitmapImageBuilder(bmp).build())
        val out = ArrayList<Det>()
        for (d in res.detections()) {
            val c = d.categories().firstOrNull() ?: continue
            out.add(Det(RectF(d.boundingBox()), c.score(), c.categoryName() ?: ""))
        }
        return out
    }

    fun close() { try { det.close() } catch (e: Throwable) { /* ignore */ } }
}
