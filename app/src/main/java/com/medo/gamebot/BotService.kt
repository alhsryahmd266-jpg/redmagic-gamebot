package com.medo.gamebot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import kotlin.math.hypot

/** Orchestrates capture -> detection -> brain -> touches, plus the floating controls. */
class BotService : Service(), Overlay.Callbacks {
    companion object {
        const val ACTION_START = "com.medo.gamebot.START"
        const val ACTION_STOP = "com.medo.gamebot.STOP"
        @Volatile var alive = false
    }

    private val ui = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var grabber: ScreenGrabber? = null
    @Volatile private var detector: Detector? = null
    @Volatile private var team: Team? = null
    @Volatile private var locator: Locator? = null
    private val brain = Brain()
    private val signals = Signals()
    private val driver = Driver()
    private var overlay: Overlay? = null
    @Volatile private var running = false
    @Volatile private var calibrating = false
    private var frameN = 0L
    private var lastFrameAt = 0L
    @Volatile private var saved: Map<String, PointF> = emptyMap()
    @Volatile private var buttons: Map<String, PointF> = emptyMap()
    @Volatile private var lastCmd = Brain.Cmd()
    private var shuttingDown = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!alive) return
            overlay?.setStats(Telemetry.line())
            ui.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> startAll(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun readData(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java) else i.getParcelableExtra("data")

    private fun startAll(intent: Intent) {
        if (alive) return
        shuttingDown = false
        createChannel()
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
        val code = intent.getIntExtra("code", 0)
        val data = readData(intent)
        if (data == null) { shutdown(); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ui.post { shutdown() } }
        }, ui)
        alive = true
        team = Team(this)
        saved = Prefs.buttons(this)
        refreshButtons()
        overlay = Overlay(this, this).also { it.show() }
        driver.layoutProvider = { currentLayout() }
        driver.start()
        val g = ScreenGrabber(this, proj) { bmp -> onFrame(bmp) }
        grabber = g
        g.handler.post {
            try {
                locator = Locator(this)
                detector = Detector(this)
            } catch (t: Throwable) {
                Telemetry.log("init failed: ${t.message}")
                ui.post { toast("فشل تجهيز الكاشف: ${t.message}") }
            }
        }
        g.start()
        ui.post(ticker)
    }

    private fun onFrame(bmp: Bitmap) {
        val t0 = SystemClock.elapsedRealtime()
        frameN++
        if (lastFrameAt != 0L) {
            val dt = (t0 - lastFrameAt).coerceAtLeast(1L)
            Telemetry.fps = Telemetry.fps * 0.9f + (1000f / dt) * 0.1f
        }
        lastFrameAt = t0
        val loc = locator
        if (loc != null && (calibrating || (running && frameN % 8L == 0L))) {
            loc.update(bmp)
            refreshButtons()
        }
        val det = detector
        val tm = team
        if (!running || calibrating || det == null || tm == null) { driver.enabled = false; return }
        if (GameA11yService.foreground != Prefs.game(this)) {
            driver.enabled = false
            Telemetry.note = "(اللعبة مش قدامك)"
            return
        }
        Telemetry.note = ""
        val ds = try { det.detect(bmp) } catch (e: Throwable) { Telemetry.log("detect: ${e.message}"); emptyList() }
        val targets = ds.filter { it.label == "person" || it.label == "enemy" }.map { d ->
            Brain.Target(d.box.centerX(), d.box.centerY(), d.box.width(), d.box.height(), d.score, tm.isFriend(bmp, d.box))
        }
        val (hit, stuck) = signals.update(bmp, t0, lastCmd.my > 0.5f)
        val cmd = brain.step(Brain.Percept(bmp.width, bmp.height, targets, hit, stuck, t0))
        lastCmd = cmd
        driver.cmd = cmd.copy(taps = emptyList())
        for (t in cmd.taps) driver.taps.add(t)
        driver.enabled = true
        Telemetry.inferMs = Telemetry.inferMs * 0.9f + (SystemClock.elapsedRealtime() - t0).toFloat() * 0.1f
    }

    private fun refreshButtons() {
        val m = HashMap<String, PointF>(saved)
        val loc = locator
        if (loc != null) for (n in Locator.NAMES) loc.position(n)?.let { m[n] = it }
        buttons = m
    }

    private fun chooseLook(sw: Int, sh: Int, pts: List<PointF>): PointF {
        var best = PointF(sw * 0.68f, sh * 0.45f)
        var bestD = -1f
        var y = sh * 0.30f
        while (y <= sh * 0.65f) {
            var x = sw * 0.55f
            while (x <= sw * 0.78f) {
                var d = 1f
                if (pts.isNotEmpty()) {
                    d = Float.MAX_VALUE
                    for (p in pts) d = minOf(d, hypot(p.x - x, p.y - y))
                }
                if (d > bestD) { bestD = d; best = PointF(x, y) }
                x += sw * 0.04f
            }
            y += sh * 0.05f
        }
        return best
    }

    private fun currentLayout(): Layout? {
        val g = grabber ?: return null
        val sw = g.realW
        val sh = g.realH
        if (sw == 0 || sh == 0 || g.capW == 0) return null
        val b = buttons
        val fireN = b["FIRE"] ?: return null
        val stickN = b["STICK"] ?: return null
        fun px(p: PointF) = PointF(p.x * sw, p.y * sh)
        val sp = px(stickN)
        val edge = b["STICK_EDGE"]
        val r = if (edge != null) {
            val e = px(edge)
            hypot(e.x - sp.x, e.y - sp.y).coerceIn(40f, 400f)
        } else 0.12f * sh
        val pts = listOfNotNull(b["FIRE"], b["JUMP"], b["RELOAD"], b["CROUCH"], b["STICK"]).map { px(it) }
        return Layout(
            sw, sh, sp, r, px(fireN),
            b["JUMP"]?.let { px(it) }, b["RELOAD"]?.let { px(it) }, b["CROUCH"]?.let { px(it) },
            chooseLook(sw, sh, pts), 0.14f * sh, sw.toFloat() / g.capW, Prefs.gain(this)
        )
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun pause() {
        running = false
        driver.enabled = false
        overlay?.setRunning(false)
    }

    // ---- Overlay callbacks ----
    override fun onToggle() {
        ui.post {
            if (running) { pause(); return@post }
            if (currentLayout() == null) {
                toast("عاير الأزرار الأول: اضغط مطوّلاً على الفقاعة ثم معايرة")
                return@post
            }
            brain.reset()
            running = true
            overlay?.setRunning(true)
        }
    }

    override fun onStop() { ui.post { pause() } }

    override fun onCalibrate() {
        ui.post {
            val g = grabber
            val tm = team
            if (g == null || tm == null || g.realW == 0) { toast("لسه بيجهّز، جرّب بعد ثانيتين"); return@post }
            pause()
            calibrating = true
            Calibrator(this, { g.snapshot() }, { detector }, tm, g.realW, g.realH) {
                calibrating = false
                saved = Prefs.buttons(this)
                locator?.reload()
                refreshButtons()
                toast("اتحفظت المعايرة")
            }.start()
        }
    }

    override fun onCopyLog() {
        ui.post {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("gamebot log", Telemetry.dump()))
            toast("اتنسخ السجل، الصقه في أي محادثة")
        }
    }

    override fun onQuit() { ui.post { shutdown() } }

    private fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        alive = false
        running = false
        driver.stop()
        grabber?.stop(); grabber = null
        try { detector?.close() } catch (e: Throwable) { /* ignore */ }
        detector = null
        try { projection?.stop() } catch (e: Throwable) { /* ignore */ }
        projection = null
        overlay?.hide(); overlay = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Throwable) { /* ignore */ }
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("gamebot", "GameBot", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotif(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, BotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val act = Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_delete), "إيقاف", stop).build()
        return Notification.Builder(this, "gamebot")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("GameBot شغال")
            .setContentText("الفقاعة العائمة للتحكم، وإيقاف كامل من هنا")
            .addAction(act)
            .setOngoing(true)
            .build()
    }
}
