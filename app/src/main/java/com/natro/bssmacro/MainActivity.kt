package com.natro.bssmacro

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val configUrl =
        "https://raw.githubusercontent.com/a66126675-collab/bss-macro/main/config.json"
    private val prefs by lazy { getSharedPreferences("macro", MODE_PRIVATE) }
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.parseColor("#14161B"))
        }
        setContentView(root)

        val saved = prefs.getString("hash", null)
        if (saved == null) {
            showKeyScreen()
        } else {
            showMain()
            thread {
                val cfg = loadConfig()
                if (cfg != null && cfg.optString("key_hash") != saved) {
                    prefs.edit().remove("hash").apply()
                    runOnUiThread { showKeyScreen() }
                }
            }
        }
    }

    private fun text(t: String, size: Float, color: Int) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(0, 16, 0, 16)
    }

    private fun showKeyScreen() {
        root.removeAllViews()
        root.addView(text("BSS Macro", 28f, Color.WHITE))
        root.addView(text("Enter your key", 16f, Color.LTGRAY))

        val input = EditText(this).apply {
            hint = "Key"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine()
        }
        root.addView(input)

        val status = text("", 16f, Color.RED)

        val getKey = Button(this).apply { text = "Get key" }
        val testKey = Button(this).apply { text = "Test key" }
        root.addView(getKey)
        root.addView(testKey)
        root.addView(status)

        getKey.setOnClickListener {
            thread {
                val url = loadConfig()?.optString("get_key_url")
                runOnUiThread {
                    if (url.isNullOrEmpty()) {
                        status.setTextColor(Color.parseColor("#FFA000"))
                        status.text = "No connection"
                    } else {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("key link", url))
                        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            }
        }

        testKey.setOnClickListener {
            val typed = input.text.toString().trim()
            thread {
                val cfg = loadConfig()
                runOnUiThread {
                    if (cfg == null) {
                        status.setTextColor(Color.parseColor("#FFA000"))
                        status.text = "No connection"
                    } else if (typed.isNotEmpty() && sha256(typed) == cfg.optString("key_hash")) {
                        prefs.edit().putString("hash", cfg.optString("key_hash")).apply()
                        showMain()
                    } else {
                        status.setTextColor(Color.RED)
                        status.text = "Incorrect key!"
                    }
                }
            }
        }
    }

    private fun showMain() {
        root.removeAllViews()
        root.addView(text("Key accepted", 24f, Color.parseColor("#4CAF50")))
        root.addView(text("Next stage: connect Roblox", 16f, Color.LTGRAY))
    }

    private fun loadConfig(): JSONObject? = try {
        val c = URL(configUrl + "?t=" + System.currentTimeMillis())
            .openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        null
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
