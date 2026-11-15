package com.medo.gamebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Used only to inject touches and to know which app is in the foreground. It never reads screen content. */
class GameA11yService : AccessibilityService() {
    companion object {
        @Volatile var instance: GameA11yService? = null
        @Volatile var foreground: String = ""
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString() ?: return
            if (p != packageName && p != "com.android.systemui") foreground = p
        }
    }

    fun dispatch(g: GestureDescription, timeoutMs: Long): Boolean {
        val sem = Semaphore(0)
        var ok = false
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; sem.release() }
            override fun onCancelled(gestureDescription: GestureDescription?) { ok = false; sem.release() }
        }
        if (!dispatchGesture(g, cb, main)) return false
        sem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
        return ok
    }
}
