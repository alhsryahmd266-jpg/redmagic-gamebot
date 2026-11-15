package com.medo.gamebot

import android.os.SystemClock
import android.util.Log

object Telemetry {
    @Volatile var fps = 0f
    @Volatile var inferMs = 0f
    @Volatile var phase = "-"
    @Volatile var enemies = 0
    @Volatile var friends = 0
    @Volatile var gOk = 0
    @Volatile var gBad = 0
    @Volatile var note = ""
    private val buf = StringBuilder()

    fun log(s: String) {
        Log.d("GameBot", s)
        synchronized(buf) {
            buf.append(SystemClock.elapsedRealtime() / 1000).append("s ").append(s).append('\n')
            if (buf.length > 40000) buf.delete(0, buf.length - 30000)
        }
    }
    fun dump(): String = synchronized(buf) { buf.toString() }
    fun line(): String =
        "FPS %.0f | كشف %.0fms | %s | أعداء %d / صديق %d | لمس %d/%d %s".format(fps, inferMs, phase, enemies, friends, gOk, gBad, note)
}
