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

    private var joy: StrokeDescription? = null
    private var hold: StrokeDescription? = null
    private var joyX = 0f
    private var joyY = 0f
    private var holdX = 0f
    private var holdY = 0f
    private var prevFrame: IntArray? = null
    private var movingAvg = 12f
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

    private fun loop() {
        var failed = false
        try {
            Thread.sleep(2000)
            val mins = num(this, "mins", 20).coerceAtLeast(1)
            val unit = when (Prefs.get(this, "length")) {
                "Short" -> 600
                "Long" -> 1500
                else -> 1000
            }
            val widthN = Prefs.get(this, "width").toIntOrNull() ?: 3
            val legs = legs(Prefs.get(this, "pattern"), widthN)
            val end = SystemClock.elapsedRealtime() + mins * 60_000L
            status = "Gathering"
            movingAvg = 12f
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

    /** Average color change between two frames in the middle of the screen (0..255). */
    private fun frameDiff(): Float? {
        val f = CaptureService.grab() ?: return null
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

        // Hold in short chunks; if the screen stops moving, the character is stuck at a wall
        val rest = (ms - 80L).coerceAtLeast(50L)
        var elapsed = 0L
        var stuckHits = 0
        prevFrame = null
        while (elapsed < rest && running) {
            val chunk = (rest - elapsed).coerceIn(50L, 300L)
            if (!holdChunk(chunk)) return false
            elapsed += chunk
            if (moving && CaptureService.active) {
                val d = frameDiff()
                if (d != null) {
                    status = "Gathering (diff " + String.format("%.1f", d) + ")"
                    if (elapsed >= 600) {
                        if (d < 8f && d < movingAvg * 0.35f) {
                            stuckHits++
                        } else {
                            stuckHits = 0
                            if (d > movingAvg * 0.5f) movingAvg = movingAvg * 0.8f + d * 0.2f
                        }
                        if (stuckHits >= 2) break
                    }
                }
            }
        }
        return true
    }

    private fun holdChunk(ms: Long): Boolean {
        val b = GestureDescription.Builder()
        var count = 0
        joy?.let { j ->
            val p = Path()
            p.moveTo(joyX, joyY)
            val nj = j.continueStroke(p, 0L, ms, true)
            joy = nj
            b.addStroke(nj)
            count++
        }
        hold?.let { h2 ->
            val p = Path()
            p.moveTo(holdX, holdY)
            val nh = h2.continueStroke(p, 0L, ms, true)
            hold = nh
            b.addStroke(nh)
            count++
        }
        if (count == 0) {
            Thread.sleep(ms)
            return true
        }
        return send(b.build())
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

    private fun send(g: GestureDescription, abortable: Boolean = true): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        if (!dispatchGesture(g, cb, handler)) {
            status = "Gesture rejected"
            return false
        }
        var waits = 0
        while (!latch.await(50, TimeUnit.MILLISECONDS)) {
            if ((abortable && !running) || ++waits > 600) break
        }
        if (!completed && running) {
            joy = null
            hold = null
        }
        return true
    }
}
