package com.natro.bssmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MacroService : AccessibilityService() {

    companion object {
        @Volatile var instance: MacroService? = null
        @Volatile var running = false
        @Volatile var status = "Idle"

        fun num(ctx: Context, key: String, def: Int): Int =
            Prefs.get(ctx, key).toIntOrNull() ?: def
    }

    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile var completed = false
    }

    private var joy: StrokeDescription? = null
    private var hold: StrokeDescription? = null
    private var joyX = 0f
    private var joyY = 0f
    private var holdX = 0f
    private var holdY = 0f
    private var prevFrame: IntArray? = null
    private var lastMove = 0f
    private var lastLimit = 0f
    private var cancels = 0
    private var walls = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopGather()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopGather()
        instance = null
        return super.onUnbind(intent)
    }

    fun startGather() {
        if (running) return
        if (Build.VERSION.SDK_INT < 26) {
            status = "Needs Android 8+"
            return
        }
        running = true
        status = "Starting"
        Thread { loop() }.start()
    }

    fun stopGather() {
        running = false
    }

    private fun screen(): Pair<Float, Float> {
        val dm = resources.displayMetrics
        return Pair(
            max(dm.widthPixels, dm.heightPixels).toFloat(),
            min(dm.widthPixels, dm.heightPixels).toFloat()
        )
    }

    /**
     * Patterns are built around the field center (0,0) and always return to it.
     * Coordinates are in "half-field" units: 1.0 = from the center to the field edge.
     * Each leg is (dirX, dirY, length in half-field units).
     */
    private fun legs(pattern: String, w: Int): List<Triple<Float, Float, Float>> {
        val pts = mutableListOf<Pair<Float, Float>>()
        when (pattern) {
            "Lines" -> {
                val s = 1.6f / w
                for (k in 0 until w) {
                    val x = (k - (w - 1) / 2f) * s
                    if (k % 2 == 0) {
                        pts.add(Pair(x, -1f))
                        pts.add(Pair(x, 1f))
                    } else {
                        pts.add(Pair(x, 1f))
                        pts.add(Pair(x, -1f))
                    }
                }
            }
            "Snake" -> {
                val s = 1.6f / w
                for (k in 0 until w) {
                    val y = (k - (w - 1) / 2f) * s
                    if (k % 2 == 0) {
                        pts.add(Pair(-1f, y))
                        pts.add(Pair(1f, y))
                    } else {
                        pts.add(Pair(1f, y))
                        pts.add(Pair(-1f, y))
                    }
                }
            }
            "Squares" -> {
                for (i in 1..w) {
                    val q = i.toFloat() / w * 0.9f
                    pts.add(Pair(-q, -q))
                    pts.add(Pair(q, -q))
                    pts.add(Pair(q, q))
                    pts.add(Pair(-q, q))
                    pts.add(Pair(-q, -q))
                }
            }
            "Spiral" -> {
                val n = w * 8
                for (k in 1..n) {
                    val a = k * (PI / 4.0)
                    val r = k.toFloat() / n * 0.9f
                    pts.add(Pair((r * cos(a)).toFloat(), (r * sin(a)).toFloat()))
                }
                val back = pts.reversed()
                pts.addAll(back)
            }
            "Diamonds" -> {
                for (i in 1..w) {
                    val q = i.toFloat() / w * 0.9f
                    pts.add(Pair(0f, -q))
                    pts.add(Pair(q, 0f))
                    pts.add(Pair(0f, q))
                    pts.add(Pair(-q, 0f))
                    pts.add(Pair(0f, -q))
                }
            }
        }
        if (pts.isEmpty()) return listOf(Triple(0f, 0f, 5f))
        pts.add(Pair(0f, 0f))
        val l = mutableListOf<Triple<Float, Float, Float>>()
        var cx = 0f
        var cy = 0f
        for (p in pts) {
            val dx = p.first - cx
            val dy = p.second - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d > 0.02f) l.add(Triple(dx / d, dy / d, d))
            cx = p.first
            cy = p.second
        }
        return l
    }

    private fun pct(x: Float): String = String.format("%.0f", x * 100f) + "%"

    private fun refreshStatus() {
        status = "Gathering | screen " +
            (if (CaptureService.active)
                "on, move " + pct(lastMove) + "/lim " + pct(lastLimit) + ", walls " + walls
            else "off") +
            " | cancels " + cancels
    }

    private fun loop() {
        var failed = false
        try {
            Thread.sleep(2000)
            cancels = 0
            walls = 0
            lastMove = 0f
            lastLimit = 0f
            val mins = num(this, "mins", 20).coerceAtLeast(1)
            // Milliseconds from the field center to its edge
            val unit = when (Prefs.get(this, "length")) {
                "Short" -> 500
                "Long" -> 1200
                else -> 800
            }
            val widthN = (Prefs.get(this, "width").toIntOrNull() ?: 3).coerceIn(1, 5)
            val legs = legs(Prefs.get(this, "pattern"), widthN)
            val end = SystemClock.elapsedRealtime() + mins * 60_000L
            refreshStatus()
            while (running && SystemClock.elapsedRealtime() < end) {
                for ((dx, dy, u) in legs) {
                    if (!running || SystemClock.elapsedRealtime() >= end) break
                    if (!step(dx, dy, (u * unit).toLong())) {
                        failed = true
                        break
                    }
                }
                if (failed) break
            }
            if (!failed) {
                status = if (SystemClock.elapsedRealtime() >= end) "Finished" else "Stopped"
            }
        } catch (e: Exception) {
            status = "Error: ${e.message}"
        } finally {
            release()
            running = false
        }
    }

    /**
     * Share of sampled points (left and right of the character, not the center with bees)
     * whose color changed since the previous check. 0 if the screen sent no new frame.
     * Null only before the very first frame.
     */
    private fun moveFraction(): Float? {
        val f = CaptureService.grab()
        if (f == null) return if (prevFrame != null) 0f else null
        val w = CaptureService.capW
        val h = CaptureService.capH
        val p = prevFrame
        prevFrame = f
        if (p == null || p.size != f.size) return null
        var changed = 0
        var cnt = 0
        val y0 = h * 15 / 100
        val y1 = h * 70 / 100
        val xs = intArrayOf(w * 22 / 100, w * 38 / 100, w * 70 / 100, w * 92 / 100)
        var region = 0
        while (region < 4) {
            var y = y0
            while (y < y1) {
                var x = xs[region]
                while (x < xs[region + 1]) {
                    val a = f[y * w + x]
                    val b = p[y * w + x]
                    val d = abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
                        abs(((a shr 8) and 255) - ((b shr 8) and 255)) +
                        abs((a and 255) - (b and 255))
                    if (d > 60) changed++
                    cnt++
                    x += 2
                }
                y += 2
            }
            region += 2
        }
        return if (cnt == 0) null else changed.toFloat() / cnt
    }

    private fun step(dx: Float, dy: Float, ms: Long): Boolean {
        val (len, shortSide) = screen()
        val cx = len * num(this, "joy_x", 14) / 100f
        val cy = shortSide * num(this, "joy_y", 70) / 100f
        val r = shortSide * num(this, "joy_r", 12) / 100f
        val hx = len * num(this, "hold_x", 65) / 100f
        val hy = shortSide * num(this, "hold_y", 40) / 100f
        holdX = hx
        holdY = hy

        val moving = dx != 0f || dy != 0f
        val n = if (moving) sqrt(dx * dx + dy * dy) else 1f
        val ex = cx + dx / n * r
        val ey = cy + dy / n * r

        // Gesture 1: quick joystick transition + keep holding the tool
        val b1 = GestureDescription.Builder()
        val pj = joy
        if (moving) {
            val p = Path()
            if (pj == null) p.moveTo(cx, cy) else p.moveTo(joyX, joyY)
            p.lineTo(ex, ey)
            val j = if (pj == null) StrokeDescription(p, 0L, 80L, true)
            else pj.continueStroke(p, 0L, 80L, true)
            joy = j
            joyX = ex
            joyY = ey
            b1.addStroke(j)
        } else if (pj != null) {
            val p = Path()
            p.moveTo(joyX, joyY)
            b1.addStroke(pj.continueStroke(p, 0L, 30L, false))
            joy = null
        }
        val ph = hold
        val hp = Path()
        hp.moveTo(hx, hy)
        val h = if (ph == null) StrokeDescription(hp, 0L, 80L, true)
        else ph.continueStroke(hp, 0L, 80L, true)
        hold = h
        b1.addStroke(h)
        if (!send(b1.build())) return false

        val rest = (ms - 80L).coerceAtLeast(50L)

        // A finger was lost (gesture cancelled): the next leg starts from scratch
        if ((moving && joy == null) || hold == null) {
            Thread.sleep(100)
            return true
        }
        val g = buildHold(rest, hx, hy, moving) ?: return true

        // No screen sharing (or standing still): one long hold
        if (!CaptureService.active || !moving) {
            refreshStatus()
            return send(g)
        }

        // Screen sharing: long hold, but watch the picture and turn at the wall.
        // The first 1.5 s of every leg teach how much the picture changes while walking.
        val pend = sendAsync(g) ?: return false
        val t0 = SystemClock.elapsedRealtime()
        val learn = mutableListOf<Float>()
        var baseline = -1f
        var stuck = 0
        prevFrame = null
        while (running && !pend.latch.await(300, TimeUnit.MILLISECONDS)) {
            val d = moveFraction() ?: continue
            lastMove = d
            val t = SystemClock.elapsedRealtime() - t0
            if (t < 1500L) {
                learn.add(d)
            } else {
                if (baseline < 0f) {
                    val s = learn.sorted()
                    baseline = if (s.isEmpty()) 0f else s[s.size * 3 / 4]
                }
                lastLimit = baseline * 0.25f
                if (baseline >= 0.06f) {
                    if (d < lastLimit) stuck++ else stuck = 0
                    if (stuck >= 2) {
                        walls++
                        cancelInFlight(cx, cy)
                        refreshStatus()
                        return true
                    }
                }
            }
            refreshStatus()
        }
        if (!pend.completed && running) {
            cancels++
            joy = null
            hold = null
        }
        return true
    }

    /** One long gesture that keeps the joystick and the tool finger pressed for [ms]. */
    private fun buildHold(ms: Long, hx: Float, hy: Float, moving: Boolean): GestureDescription? {
        val b = GestureDescription.Builder()
        if (moving) {
            val pj = joy ?: return null
            val p = Path()
            p.moveTo(joyX, joyY)
            val j = pj.continueStroke(p, 0L, ms, true)
            joy = j
            b.addStroke(j)
        }
        val ph = hold ?: return null
        val hp = Path()
        hp.moveTo(hx, hy)
        val h = ph.continueStroke(hp, 0L, ms, true)
        hold = h
        b.addStroke(h)
        return b.build()
    }

    /** A tiny tap cancels the running long gesture and lifts all fingers. */
    private fun cancelInFlight(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(StrokeDescription(p, 0L, 10L, false))
            .build()
        joy = null
        hold = null
        send(g, false)
    }

    private fun release() {
        try {
            val b = GestureDescription.Builder()
            var any = false
            joy?.let {
                val p = Path()
                p.moveTo(joyX, joyY)
                b.addStroke(it.continueStroke(p, 0L, 20L, false))
                any = true
            }
            hold?.let {
                val p = Path()
                p.moveTo(holdX, holdY)
                b.addStroke(it.continueStroke(p, 0L, 20L, false))
                any = true
            }
            joy = null
            hold = null
            prevFrame = null
            if (any) send(b.build(), false)
        } catch (e: Exception) {
            joy = null
            hold = null
        }
    }

    private fun sendAsync(g: GestureDescription): Pending? {
        val pend = Pending()
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                pend.completed = true
                pend.latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                pend.latch.countDown()
            }
        }
        if (!dispatchGesture(g, cb, handler)) {
            status = "Gesture rejected"
            return null
        }
        return pend
    }

    private fun send(g: GestureDescription, abortable: Boolean = true): Boolean {
        val pend = sendAsync(g) ?: return false
        var waits = 0
        while (!pend.latch.await(50, TimeUnit.MILLISECONDS)) {
            if ((abortable && !running) || ++waits > 600) break
        }
        if (!pend.completed && running) {
            cancels++
            joy = null
            hold = null
        }
        return true
    }
}
