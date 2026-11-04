package com.natro.bssmacro

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var root: LinearLayout
    private var tab = "home"
    private var config: JSONObject? = null
    private var lastAnswer = ""
    private val fields = listOf(
        "Sunflower", "Dandelion", "Mushroom", "Blue Flower", "Clover", "Spider",
        "Strawberry", "Bamboo", "Pineapple", "Stump", "Cactus", "Pumpkin",
        "Pine Tree", "Rose", "Mountain Top", "Coconut", "Pepper"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        setContentView(root)

        val saved = Prefs.get(this, "hash")
        if (saved.isEmpty()) {
            showKeyScreen()
        } else {
            showMain()
            thread {
                val cfg = loadConfig()
                if (cfg != null) {
                    config = cfg
                    if (cfg.optString("key_hash") != saved) {
                        Prefs.remove(this, "hash")
                        runOnUiThread { showKeyScreen() }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.get(this, "hash").isNotEmpty()) showMain()
    }

    // ---------- Key screen ----------

    private fun showKeyScreen() {
        root.removeAllViews()
        root.gravity = Gravity.CENTER_VERTICAL
        val status = label("", 15f, Theme.RED).apply { gravity = Gravity.CENTER }
        var typed = ""
        root.addView(label("BSS Macro", 34f, Theme.ACCENT, true).apply { gravity = Gravity.CENTER })
        root.addView(
            label("Enter your key to continue", 15f, Theme.MUTED).apply { gravity = Gravity.CENTER },
            params(4)
        )
        root.addView(input("Key") { typed = it.trim() }, params(28))
        root.addView(button("Get key", false) { getKey(status) }, params(14))
        root.addView(button("Test key") { testKey(typed, status) }, params(10))
        root.addView(status, params(14))
    }

    private fun getKey(status: TextView) {
        thread {
            val url = loadConfig()?.optString("get_key_url")
            runOnUiThread {
                if (url.isNullOrEmpty()) {
                    status.setTextColor(Theme.ACCENT)
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

    private fun testKey(typed: String, status: TextView) {
        thread {
            val cfg = loadConfig()
            runOnUiThread {
                if (cfg == null) {
                    status.setTextColor(Theme.ACCENT)
                    status.text = "No connection"
                } else if (typed.isNotEmpty() && sha256(typed) == cfg.optString("key_hash")) {
                    config = cfg
                    Prefs.put(this, "hash", cfg.optString("key_hash"))
                    showMain()
                    if (!Settings.canDrawOverlays(this)) openOverlaySettings()
                } else {
                    status.setTextColor(Theme.RED)
                    status.text = "Incorrect key!"
                }
            }
        }
    }

    // ---------- Main screen ----------

    private fun showMain() {
        root.removeAllViews()
        root.gravity = Gravity.TOP or Gravity.START

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            label("BSS Macro", 24f, Theme.ACCENT, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(label("Exit", 14f, Theme.MUTED, true).apply {
            setPadding(dp(16), dp(8), 0, dp(8))
            setOnClickListener { confirmExit() }
        })
        root.addView(header)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(16))
        }
        scroll.addView(body)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        when (tab) {
            "home" -> homeTab(body)
            "gather" -> gatherTab(body)
            "ai" -> aiTab(body)
            else -> settingsTab(body)
        }
        root.addView(tabBar())
    }

    private fun tabBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Theme.CARD, 16)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        listOf("home" to "Home", "gather" to "Gather", "ai" to "AI", "settings" to "Settings")
            .forEach { (id, name) ->
                val t = label(name, 14f, if (tab == id) Color.BLACK else Theme.MUTED, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    if (tab == id) background = rounded(Theme.ACCENT, 12)
                    setOnClickListener {
                        tab = id
                        showMain()
                    }
                }
                bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        return bar
    }

    private fun step(
        title: String, sub: String, ok: Boolean, btnText: String?, onClick: () -> Unit
    ): LinearLayout {
        val views = mutableListOf<View>(
            label(title, 17f, if (ok) Theme.GREEN else Color.WHITE, true),
            label(sub, 14f, Theme.MUTED)
        )
        if (btnText != null) views.add(button(btnText, !ok, onClick))
        return card(*views.toTypedArray())
    }

    private fun homeTab(b: LinearLayout) {
        val overlayOk = Settings.canDrawOverlays(this)
        val accOk = MacroService.instance != null
        val robloxOk = Roblox.installed(this)
        b.addView(
            step(
                "1. Overlay permission",
                if (overlayOk) "Granted" else "Needed for the floating button",
                overlayOk,
                if (overlayOk) null else "Open settings"
            ) { openOverlaySettings() }
        )
        b.addView(
            step(
                "2. Accessibility (taps and swipes)",
                if (accOk) "Enabled" else "Needed so the macro can control the screen",
                accOk,
                if (accOk) null else "Open accessibility settings"
            ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            params(12)
        )
        b.addView(
            step(
                "3. Connect Roblox",
                if (robloxOk) "Opens Bee Swarm Simulator automatically."
                else "Roblox app not found. Install it from Google Play.",
                false,
                "Connect Roblox"
            ) { connectRoblox() },
            params(12)
        )
        b.addView(
            step(
                "4. Floating button",
                "Start the cube. In Roblox tap it and press Start gather.",
                false,
                "Start floating button"
            ) { startFloating() },
            params(12)
        )
    }

    private fun gatherTab(b: LinearLayout) {
        fun dd(key: String, items: List<String>) =
            dropdown(items, Prefs.get(this, key)) { Prefs.put(this, key, it) }

        fun num(key: String, hintText: String) =
            input(hintText, Prefs.get(this, key), numeric = true) { Prefs.put(this, key, it) }

        fun cal(key: String, def: Int) =
            input(def.toString(), Prefs.get(this, key).ifEmpty { def.toString() }, numeric = true) {
                Prefs.put(this, key, it)
            }

        b.addView(
            card(
                label("Gathering", 17f, Color.WHITE, true),
                field("Field", dd("field", fields)),
                field(
                    "Pattern",
                    dd("pattern", listOf("Squares", "Lines", "Spiral", "Snake", "Diamonds", "Stationary"))
                ),
                field("Length", dd("length", listOf("Short", "Medium", "Long"))),
                field("Width", dd("width", listOf("1", "2", "3", "4", "5"))),
                field("Gather minutes", num("mins", "20")),
                field("Return to hive at pack %", num("pack", "95")),
                field("Return to hive by", dd("tohive", listOf("Walk", "Reset"))),
                field(
                    "Sprinkler start",
                    dd("sprinkler", listOf("Center", "Upper Left", "Upper Right", "Lower Left", "Lower Right"))
                )
            )
        )
        b.addView(
            card(
                label("Calibration (% of screen)", 17f, Color.WHITE, true),
                label("Where the macro touches the screen in landscape. Adjust if it misses.", 13f, Theme.MUTED),
                field("Joystick start X", cal("joy_x", 14)),
                field("Joystick start Y", cal("joy_y", 70)),
                field("Joystick push distance", cal("joy_r", 12)),
                field("Tool hold X", cal("hold_x", 65)),
                field("Tool hold Y", cal("hold_y", 40))
            ),
            params(12)
        )
        b.addView(
            card(
                label(
                    "Return to hive at pack % needs screen reading and comes later. " +
                        "For now gathering stops after the minutes you set.",
                    13f, Theme.MUTED
                )
            ),
            params(12)
        )
    }

    private fun aiTab(b: LinearLayout) {
        var question = ""
        val answer = label(lastAnswer, 14f, Color.WHITE)
        b.addView(
            card(
                label("AI helper (Gemini)", 17f, Color.WHITE, true),
                label("Describe your goal and get gather settings advice.", 14f, Theme.MUTED)
            )
        )
        b.addView(
            card(
                input("e.g. How do I get more honey per hour?", single = false) { question = it },
                button("Ask Gemini") { askGemini(question, answer) },
                answer
            ),
            params(12)
        )
    }

    private fun settingsTab(b: LinearLayout) {
        b.addView(
            card(
                label("Gemini AI", 17f, Color.WHITE, true),
                label("Free key: aistudio.google.com/apikey", 13f, Theme.MUTED),
                input("Gemini API key", Prefs.get(this, "gemini_key"), secret = true) {
                    Prefs.put(this, "gemini_key", it.trim())
                }
            )
        )
        b.addView(
            card(
                label("Private server link (optional)", 17f, Color.WHITE, true),
                label("If empty, Roblox opens Bee Swarm Simulator.", 13f, Theme.MUTED),
                input("https://www.roblox.com/games/...", Prefs.get(this, "ps_link")) {
                    Prefs.put(this, "ps_link", it.trim())
                }
            ),
            params(12)
        )
        b.addView(
            card(
                label("Account", 17f, Color.WHITE, true),
                button("Change key", false) {
                    Prefs.remove(this, "hash")
                    showKeyScreen()
                },
                button("Exit app", false) { confirmExit() }
            ),
            params(12)
        )
    }

    // ---------- Actions ----------

    private fun summary(): String =
        listOf("field", "pattern", "length", "width", "mins", "pack", "tohive", "sprinkler")
            .joinToString(", ") { "$it=${Prefs.get(this, it)}" }

    private fun askGemini(question: String, out: TextView) {
        val key = Prefs.get(this, "gemini_key")
        if (key.isEmpty()) {
            out.text = "Add your Gemini API key in Settings first."
            return
        }
        if (question.isBlank()) {
            out.text = "Type a question first."
            return
        }
        out.text = "Thinking..."
        val sys = "You are the AI helper inside a mobile macro app for Roblox Bee Swarm Simulator. " +
            "Help the user optimize their gathering settings. Answer briefly in the user's language. " +
            "Current settings: ${summary()}"
        thread {
            val model = (config ?: loadConfig())?.optString("gemini_model").orEmpty()
                .ifEmpty { "gemini-flash-latest" }
            val res = Gemini.ask(key, model, sys, question)
            lastAnswer = res
            runOnUiThread { out.text = res }
        }
    }

    private fun connectRoblox() {
        if (!Roblox.open(this, Prefs.get(this, "ps_link"))) {
            Toast.makeText(this, "Roblox is not installed", Toast.LENGTH_LONG).show()
        }
    }

    private fun startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        val i = Intent(this, FloatingService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        moveTaskToBack(true)
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun confirmExit() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Are you sure?")
            .setPositiveButton("Yes") { _, _ ->
                MacroService.instance?.stopGather()
                stopService(Intent(this, FloatingService::class.java))
                finishAndRemoveTask()
            }
            .setNegativeButton("No", null)
            .show()
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
