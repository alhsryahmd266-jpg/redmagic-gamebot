package com.medo.gamebot

import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * Brain = Utility AI (per-tick action scoring) + a strategic state machine
 * SCOUT -> ENGAGE -> REGROUP -> AMBUSH + tactical memory (breadcrumbs of recent movement and the
 * last-seen enemy direction), so a retreat walks back the way we came and an ambush watches
 * the side the enemy came from. Everything is local; small random jitter keeps play unpredictable.
 */
class Brain {
    enum class Phase(val ar: String) { SCOUT("استطلاع"), ENGAGE("هجوم"), REGROUP("تراجع"), AMBUSH("كمين") }

    data class Target(val cx: Float, val cy: Float, val w: Float, val h: Float, val score: Float, val friend: Boolean)
    data class Percept(val w: Int, val h: Int, val targets: List<Target>, val hit: Boolean, val stuck: Boolean, val now: Long)
    data class Cmd(
        val mx: Float = 0f,
        val my: Float = 0f,
        val lx: Float = 0f,
        val ly: Float = 0f,
        val fire: Boolean = false,
        val taps: List<String> = emptyList()
    )

    private class Crumb(val mx: Float, val my: Float, val ms: Long)
    private class Aim(val lx: Float, val ly: Float, val on: Boolean)

    var phase = Phase.SCOUT
        private set
    private var phaseSince = 0L
    private val rnd = Random(System.nanoTime())
    private val hits = ArrayDeque<Long>()
    private val crumbs = ArrayDeque<Crumb>()
    private var crumbMs = 0L
    private var retreat = ArrayList<Crumb>()
    private var retreatIdx = 0
    private var retreatLeft = 0L
    private var lastEnemy: Target? = null
    private var lastEnemyAt = 0L
    private var prevCx = 0f
    private var prevCy = 0f
    private var prevT = 0L
    private var vx = 0f
    private var vy = 0f
    private var strafeDir = 1
    private var strafeUntil = 0L
    private var firing = false
    private var fireUntil = 0L
    private var coolUntil = 0L
    private var nextJump = 0L
    private var turnDir = 1
    private var turnUntil = 0L
    private var lastT = 0L

    fun reset() {
        phase = Phase.SCOUT; phaseSince = 0L; hits.clear(); crumbs.clear(); crumbMs = 0L
        retreat = ArrayList(); retreatIdx = 0; lastEnemy = null; lastEnemyAt = 0L
        prevT = 0L; firing = false; lastT = 0L
    }

    fun step(p: Percept): Cmd {
        val now = p.now
        val dt = if (lastT == 0L) 0.033f else (now - lastT).coerceIn(1L, 200L) / 1000f
        lastT = now
        val enemies = p.targets.filter { !it.friend && it.score >= 0.38f && it.h >= p.h * 0.05f }
        Telemetry.enemies = enemies.size
        Telemetry.friends = p.targets.count { it.friend }
        if (p.hit) hits.addLast(now)
        while (hits.isNotEmpty() && now - hits.first() > 4000L) hits.removeFirst()
        val enemy = pick(enemies, p)
        if (enemy != null) { lastEnemy = enemy; lastEnemyAt = now }

        when (phase) {
            Phase.SCOUT -> if (enemy != null) enter(Phase.ENGAGE, now)
            Phase.ENGAGE -> {
                if (enemies.size >= 3 || hits.size >= 3) enter(Phase.REGROUP, now)
                else if (enemy == null && now - lastEnemyAt > 2500L) enter(Phase.SCOUT, now)
            }
            Phase.REGROUP -> {
                val t = now - phaseSince
                if ((t > 2800L && hits.isEmpty()) || t > 4500L) enter(Phase.AMBUSH, now)
            }
            Phase.AMBUSH -> {
                if (enemy != null) enter(Phase.ENGAGE, now)
                else if (now - phaseSince > 4000L) enter(Phase.SCOUT, now)
            }
        }
        Telemetry.phase = phase.ar

        val cmd = when (phase) {
            Phase.SCOUT -> scout(p, now)
            Phase.ENGAGE -> engage(p, enemy, now, dt)
            Phase.REGROUP -> regroup(p, enemy, now, dt)
            Phase.AMBUSH -> ambush(p, enemy, now, dt)
        }
        if (phase != Phase.REGROUP) recordCrumb(cmd, (dt * 1000).toLong())
        return cmd
    }

    private fun enter(next: Phase, now: Long) {
        if (next == Phase.REGROUP) buildRetreat()
        if (next == Phase.ENGAGE) { firing = false; prevT = 0L }
        phase = next
        phaseSince = now
        Telemetry.log("phase -> ${next.name}")
    }

    private fun pick(list: List<Target>, p: Percept): Target? {
        if (list.isEmpty()) return null
        val cx = p.w / 2f
        val cy = p.h / 2f
        return list.maxByOrNull { t ->
            val d = (abs(t.cx - cx) + abs(t.cy - cy)) / p.w
            t.score + (t.h / p.h) * 0.8f - d * 1.2f
        }
    }

    private fun recordCrumb(c: Cmd, ms: Long) {
        crumbs.addLast(Crumb(c.mx, c.my, ms))
        crumbMs += ms
        while (crumbMs > 8000L && crumbs.isNotEmpty()) crumbMs -= crumbs.removeFirst().ms
    }

    private fun buildRetreat() {
        retreat = ArrayList()
        retreatIdx = 0
        var acc = 0L
        var i = crumbs.size - 1
        while (i >= 0 && acc < 3000L) {
            val c = crumbs[i]
            if (abs(c.mx) + abs(c.my) >= 0.1f) { retreat.add(Crumb(-c.mx, -c.my, c.ms)); acc += c.ms }
            i--
        }
        retreatLeft = if (retreat.isNotEmpty()) retreat[0].ms else 0L
    }

    private fun aim(p: Percept, t: Target, now: Long, dt: Float): Aim {
        val ax = t.cx
        val ay = t.cy - t.h * 0.28f
        if (prevT != 0L && now - prevT < 300L) {
            val k = 0.5f
            vx = vx * (1 - k) + ((t.cx - prevCx) / dt) * k
            vy = vy * (1 - k) + ((t.cy - prevCy) / dt) * k
        } else { vx = 0f; vy = 0f }
        prevCx = t.cx; prevCy = t.cy; prevT = now
        val ex = ax + vx * 0.09f - p.w / 2f
        val ey = ay + vy * 0.09f - p.h / 2f
        val maxStep = p.w * 0.10f
        val lx = (ex * 0.55f).coerceIn(-maxStep, maxStep)
        val ly = (ey * 0.45f).coerceIn(-maxStep, maxStep)
        val on = abs(ax - p.w / 2f) < t.w * 0.40f && abs(ay - p.h / 2f) < t.h * 0.30f
        return Aim(lx, ly, on)
    }

    private fun fireLogic(on: Boolean, now: Long): Boolean {
        if (!firing && on && now >= coolUntil) { firing = true; fireUntil = now + rnd.nextLong(220L, 480L) }
        if (firing && (now >= fireUntil || !on)) { firing = false; coolUntil = now + rnd.nextLong(70L, 170L) }
        return firing && on
    }

    private fun engage(p: Percept, enemy: Target?, now: Long, dt: Float): Cmd {
        val t = enemy ?: lastEnemy?.takeIf { now - lastEnemyAt < 500L } ?: return scout(p, now)
        val a = aim(p, t, now, dt)
        val fire = fireLogic(a.on, now)
        val dist = t.h / p.h
        if (now >= strafeUntil) {
            strafeDir = if (rnd.nextBoolean()) 1 else -1
            strafeUntil = now + rnd.nextLong(450L, 1300L)
        }
        val back = when {
            dist > 0.42f -> 0.9f
            dist > 0.30f -> 0.35f
            else -> 0f
        }
        val adv = when {
            dist < 0.10f -> 0.8f
            dist < 0.16f -> 0.4f
            else -> 0f
        }
        val taps = ArrayList<String>()
        if (dist >= 0.12f && dist <= 0.35f && now >= nextJump && rnd.nextInt(100) < 35) {
            taps.add("JUMP")
            nextJump = now + rnd.nextLong(1200L, 2800L)
        }
        return Cmd(strafeDir * 0.9f, (adv - back).coerceIn(-1f, 1f), a.lx, a.ly, fire, taps)
    }

    private fun scout(p: Percept, now: Long): Cmd {
        val taps = ArrayList<String>()
        var mx = 0f
        var my = 0.85f
        val le = lastEnemy
        var lx = if (le != null && now - lastEnemyAt < 3000L) sign(le.cx - p.w / 2f) * p.w * 0.010f
        else turnDir * p.w * 0.008f
        if (now > turnUntil && rnd.nextInt(100) < 2) {
            turnDir = -turnDir
            turnUntil = now + rnd.nextLong(1500L, 3500L)
        }
        if (p.stuck) {
            lx = turnDir * p.w * 0.05f
            my = 0.3f
            mx = if (rnd.nextBoolean()) 0.8f else -0.8f
            if (rnd.nextInt(100) < 25) turnDir = -turnDir
        }
        if (now >= nextJump) { taps.add("JUMP"); nextJump = now + rnd.nextLong(2500L, 5000L) }
        return Cmd(mx, my, lx, 0f, false, taps)
    }

    private fun regroup(p: Percept, enemy: Target?, now: Long, dt: Float): Cmd {
        var mx: Float
        var my: Float
        if (retreatIdx < retreat.size) {
            val c = retreat[retreatIdx]
            mx = c.mx; my = c.my
            retreatLeft -= (dt * 1000).toLong()
            if (retreatLeft <= 0L) {
                retreatIdx++
                if (retreatIdx < retreat.size) retreatLeft = retreat[retreatIdx].ms
            }
        } else {
            mx = if ((now / 600L) % 2L == 0L) 0.7f else -0.7f
            my = -0.6f
        }
        var lx = 0f
        var ly = 0f
        var fire = false
        if (enemy != null) {
            val a = aim(p, enemy, now, dt)
            lx = a.lx; ly = a.ly
            fire = fireLogic(a.on, now)
        } else {
            val le = lastEnemy
            if (le != null && now - lastEnemyAt < 2500L) lx = sign(le.cx - p.w / 2f) * p.w * 0.012f
        }
        return Cmd(mx, my, lx, ly, fire)
    }

    private fun ambush(p: Percept, enemy: Target?, now: Long, dt: Float): Cmd {
        if (enemy != null) {
            val a = aim(p, enemy, now, dt)
            return Cmd(0f, 0f, a.lx, a.ly, fireLogic(a.on, now))
        }
        val le = lastEnemy
        val lx = if (le != null) sign(le.cx - p.w / 2f) * p.w * 0.004f else 0f
        return Cmd(0f, 0f, lx, 0f, false)
    }
}
