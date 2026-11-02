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
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class FloatingService : Service() {

    private var wm: WindowManager? = null
    private var container: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (container == null) addOverlay()
        return START_STICKY
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
        val dp = resources.displayMetrics.density

        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cube = CubeView(this)
        val size = (64 * dp).toInt()
        c.addView(cube, LinearLayout.LayoutParams(size, size))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE14161B"))
            val pad = (12 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        panel.addView(TextView(this).apply {
            text = "BSS Macro\nMenu: next stage"
            setTextColor(Color.WHITE)
        })
        panel.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { stopSelf() }
        })
        c.addView(panel)

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

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        cube.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
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
                        panel.visibility =
                            if (panel.visibility == View.GONE) View.VISIBLE else View.GONE
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
