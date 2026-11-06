package com.natro.bssmacro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.max
import kotlin.math.min

class CaptureService : Service() {

    companion object {
        @Volatile var active = false
        @Volatile var capW = 0
        @Volatile var capH = 0
        @Volatile private var reader: ImageReader? = null

        /** New frame as RGB ints (capW * capH), or null if there is no new frame yet. */
        fun grab(): IntArray? {
            val r = reader ?: return null
            val img = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return null
            try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val rowStride = plane.rowStride
                val pix = plane.pixelStride
                val w = capW
                val h = capH
                val out = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val o = y * rowStride + x * pix
                        val rr = buf.get(o).toInt() and 255
                        val gg = buf.get(o + 1).toInt() and 255
                        val bb = buf.get(o + 2).toInt() and 255
                        out[y * w + x] = (rr shl 16) or (gg shl 8) or bb
                    }
                }
                return out
            } catch (e: Exception) {
                return null
            } finally {
                img.close()
            }
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var closed = false

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun readData(i: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i?.getParcelableExtra("data", Intent::class.java)
        else i?.getParcelableExtra<Intent>("data")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = readData(intent)
        if (data == null) {
            MacroService.status = "Capture error: no permission data"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
            begin(code, data)
            MacroService.status = "Screen sharing on"
        } catch (e: Exception) {
            MacroService.status = "Capture error: ${e.javaClass.simpleName} ${e.message}"
            shutdown()
        }
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
            .setContentText("Screen sharing is on")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, n)
        }
    }

    private fun begin(code: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                shutdown()
            }
        }, Handler(Looper.getMainLooper()))

        val dm = resources.displayMetrics
        val big = max(dm.widthPixels, dm.heightPixels)
        val small = min(dm.widthPixels, dm.heightPixels)
        capW = big / 4
        capH = small / 4
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r
        display = mp.createVirtualDisplay(
            "bss-capture", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        active = true
    }

    private fun shutdown() {
        if (closed) return
        closed = true
        active = false
        try { display?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        reader = null
        try { projection?.stop() } catch (e: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
