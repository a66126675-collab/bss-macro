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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
    private var lastDiff = 0f
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

    private fun legs(pattern: String, w: Int): List<Triple<Float, Float, Float>> {
        val l = mutableListOf<Triple<Float, Float, Float>>()
        when (pattern) {
            "Lines", "Snake" -> {
                repeat(w) {
                    l.add(Triple(0f, -1f, 2f))
                    l.add(Triple(1f, 0f, 0.5f))
                    l.add(Triple(0f, 1f, 2f))
                    l.add(Triple(1f, 0f, 0.5f))
                }
                l.add(Triple(-1f, 0f, w.toFloat()))
            }
            "Squares" -> {
                for (i in 1..w) {
                    val s = i * 0.6f
                    l.add(Triple(0f, -1f, s))
                    l.add(Triple(1f, 0f, s))
                    l.add(Triple(0f, 1f, s))
                    l.add(Triple(-1f, 0f, s))
                }
            }
            "Spiral" -> {
                val dirs = listOf(
                    Pair(1f, 0f), Pair(0f, -1f), Pair(-1f, 0f), Pair(0f, 1f)
                )
                for (k in 0 until 4 * w) {
                    val d = dirs[k % 4]
                    l.add(Triple(d.first, d.second, (k / 2 + 1) * 0.5f))
                }
                val back = l.reversed().map { Triple(-it.first, -it.second, it.third) }
                l.addAll(back)
            }
            "Diamonds" -> {
                for (i in 1..w) {
                    val s = i * 0.5f
                    l.add(Triple(1f, -1f, s))
                    l.add(Triple(1f, 1f, s))
                    l.add(Triple(-1f, 1f, s))
                    l.add(Triple(-1f, -1f, s))
                }
            }
            else -> l.add(Triple(0f, 0f, 5f))
        }
        return l
    }

    private fun f1(x: Float): String = String.format("%.1f", x)

    private fun refreshStatus() {
        status = "Gathering | screen " +
            (if (CaptureService.active)
                "on, diff " + f1(lastDiff) + "/" + f1(lastLimit) + ", walls " + walls
            else "off") +
            " | cancels " + cancels
    }

    private fun loop() {
        var failed = false
        try {
            Thread.sleep(2000)
            cancels = 0
            walls = 0
            lastDiff = 0f
            lastLimit = 0f
            val mins = num(this, "mins", 20).coerceAtLeast(1)
            val base = when (Prefs.get(this, "length")) {
                "Short" -> 600
                "Long" -> 1500
                else -> 1000
            }
            // With screen sharing legs are longer: the macro turns at the wall by itself
            val unit = if (CaptureService.active) base * 3 / 2 else base
            val widthN = Prefs.get(this, "width").toIntOrNull() ?: 3
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
     * Average color change since the previous check in the middle of the screen (0..255).
     * No new frame means the picture did not change at all (0). Null only before the first frame.
     */
    private fun frameDiff(): Float? {
        val f = CaptureService.grab()
        if (f == null) return if (prevFrame != null) 0f else null
        val w = CaptureService.capW
        val h = CaptureService.capH
        val p = prevFrame
        prevFrame = f
        if (p == null || p.size != f.size) return null
        var sum = 0L
        var cnt = 0
        val x0 = w * 25 / 100
        val x1 = w * 90 / 100
        val y0 = h * 10 / 100
        val y1 = h * 75 / 100
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val a = f[y * w + x]
                val b = p[y * w + x]
                sum += abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
                    abs(((a shr 8) and 255) - ((b shr 8) and 255)) +
                    abs((a and 255) - (b and 255))
                cnt++
                x += 3
            }
            y += 3
        }
        return if (cnt == 0) null else sum.toFloat() / (cnt * 3)
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
        // First 1.5 s of every leg teach how much the picture changes while walking.
        val pend = sendAsync(g) ?: return false
        val t0 = SystemClock.elapsedRealtime()
        val learn = mutableListOf<Float>()
        var baseline = -1f
        var stuck = 0
        prevFrame = null
        while (running && !pend.latch.await(300, TimeUnit.MILLISECONDS)) {
            val d = frameDiff() ?: continue
            lastDiff = d
            val t = SystemClock.elapsedRealtime() - t0
            if (t < 1500L) {
                learn.add(d)
            } else {
                if (baseline < 0f) {
                    val s = learn.sorted()
                    baseline = if (s.isEmpty()) d else s[s.size * 3 / 4]
                }
                lastLimit = baseline * 0.4f
                if (baseline >= 3f) {
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
