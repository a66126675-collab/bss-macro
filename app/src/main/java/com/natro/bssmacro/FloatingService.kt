package com.natro.bssmacro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

class FloatingService : Service() {

    private var wm: WindowManager? = null
    private var container: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (container == null) addOverlay()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("macro", "BSS Macro", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "macro")
        else Notification.Builder(this)
        val n = b.setContentTitle("BSS Macro")
            .setContentText("Floating button is active")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun addOverlay() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager

        // Cube on the left, panel to the right of it (so it fits in landscape)
        val c = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cube = CubeView(this)
        c.addView(cube, LinearLayout.LayoutParams(dp(64), dp(64)))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Theme.CARD, 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        c.addView(
            panel,
            LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(8) }
        )

        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val confirm = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        fun sb(t: String, primary: Boolean = false, onClick: () -> Unit): TextView =
            button(t, primary, onClick).apply {
                textSize = 13f
                setPadding(dp(6), dp(9), dp(6), dp(9))
            }

        fun row(a: View, b: View): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(4) })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(4) })
        }

        val statusTv = label("", 12f, Theme.MUTED)
        val sizeTv = label("", 13f, Color.WHITE, true)
        val startBtn = sb("Start gather", true) {}

        var savedX = 0
        var savedY = 0

        fun refresh() {
            startBtn.text = if (MacroService.running) "Stop gather" else "Start gather"
            statusTv.text = MacroService.status + " | Screen: " +
                (if (CaptureService.active) "on" else "off")
            sizeTv.text = "Field size: ${MacroService.halfMs(this)} ms"
        }

        fun openPanel() {
            refresh()
            confirm.visibility = View.GONE
            menu.visibility = View.VISIBLE
            panel.visibility = View.VISIBLE
            savedX = lp.x
            savedY = lp.y
            lp.y = dp(4)
            val sw = resources.displayMetrics.widthPixels
            val need = dp(64 + 8 + 300 + 8)
            if (lp.x + need > sw) lp.x = max(0, sw - need)
            manager.updateViewLayout(c, lp)
        }

        fun closePanel() {
            panel.visibility = View.GONE
            lp.x = savedX
            lp.y = savedY
            manager.updateViewLayout(c, lp)
        }

        fun adjust(delta: Int) {
            val cur = Prefs.get(this, "half_adj").toIntOrNull() ?: 0
            Prefs.put(this, "half_adj", (cur + delta).toString())
            refresh()
        }

        startBtn.setOnClickListener {
            val m = MacroService.instance
            if (m == null) {
                Toast.makeText(this, "Enable Accessibility for BSS Macro first", Toast.LENGTH_LONG).show()
            } else if (MacroService.running) {
                m.stopGather()
                startBtn.text = "Start gather"
            } else {
                m.startGather()
                closePanel()
            }
        }

        menu.addView(label("BSS Macro", 16f, Theme.ACCENT, true))
        menu.addView(statusTv, params(2))
        menu.addView(sizeTv, params(6))
        menu.addView(row(sb("Field -") { adjust(-100) }, sb("Field +") { adjust(100) }), params(4))
        menu.addView(row(startBtn, sb("Share screen") {
            val i = Intent(this, CaptureRequestActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            closePanel()
        }), params(8))
        menu.addView(row(sb("Test screen") {
            val m = MacroService.instance
            if (m == null) {
                Toast.makeText(this, "Enable Accessibility for BSS Macro first", Toast.LENGTH_LONG).show()
            } else {
                m.testCapture()
                statusTv.text = "Testing screen for 3 s..."
                Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 3600)
            }
        }, sb("Open Roblox") {
            Roblox.open(this, Prefs.get(this, "ps_link"))
        }), params(6))
        menu.addView(row(sb("Open app") {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
        }, sb("Close") {
            menu.visibility = View.GONE
            confirm.visibility = View.VISIBLE
        }), params(6))

        confirm.addView(label("Are you sure?", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
        })
        confirm.addView(row(
            sb("Yes", true) {
                MacroService.instance?.stopGather()
                stopService(Intent(this, CaptureService::class.java))
                stopSelf()
                Handler(Looper.getMainLooper()).postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 400)
            },
            sb("No") {
                confirm.visibility = View.GONE
                menu.visibility = View.VISIBLE
            }
        ), params(12))

        panel.addView(menu)
        panel.addView(confirm)

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        cube.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        manager.updateViewLayout(c, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (panel.visibility == View.GONE) openPanel() else closePanel()
                    }
                    true
                }
                else -> false
            }
        }

        manager.addView(c, lp)
        container = c
    }

    override fun onDestroy() {
        container?.let { wm?.removeView(it) }
        container = null
        super.onDestroy()
    }
}

class CubeView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = RectF(4f, 4f, w - 4f, h - 4f)
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#6BFF00")
        canvas.drawRoundRect(r, 12f, 12f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        p.color = Color.parseColor("#55CC00")
        canvas.drawRoundRect(r, 12f, 12f, p)
        p.style = Paint.Style.FILL
        p.color = Color.BLACK
        canvas.drawCircle(w * 0.35f, h * 0.40f, w * 0.06f, p)
        canvas.drawCircle(w * 0.65f, h * 0.40f, w * 0.06f, p)
        p.style = Paint.Style.STROKE
        canvas.drawLine(w * 0.30f, h * 0.70f, w * 0.70f, h * 0.74f, p)
    }
}
