package com.natro.bssmacro

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

object Theme {
    val BG = Color.parseColor("#14161B")
    val CARD = Color.parseColor("#1F232B")
    val ACCENT = Color.parseColor("#FFB300")
    val GREEN = Color.parseColor("#4CAF50")
    val RED = Color.parseColor("#FF4D4D")
    val MUTED = Color.parseColor("#9AA0A6")
}

object Prefs {
    private val defaults = mapOf(
        "field" to "Sunflower", "pattern" to "Squares", "length" to "Medium",
        "width" to "3", "mins" to "20", "pack" to "95",
        "tohive" to "Walk", "sprinkler" to "Center"
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("macro", Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): String =
        sp(ctx).getString(key, defaults[key] ?: "") ?: ""

    fun put(ctx: Context, key: String, value: String) {
        sp(ctx).edit().putString(key, value).apply()
    }

    fun remove(ctx: Context, key: String) {
        sp(ctx).edit().remove(key).apply()
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radius: Int = 14, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

fun Context.params(top: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

fun Context.label(
    t: String, size: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false
): TextView = TextView(this).apply {
    text = t
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

fun Context.button(t: String, primary: Boolean = true, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(if (primary) Theme.ACCENT else Color.parseColor("#2C313B"), 12)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
    }

fun Context.card(vararg views: View): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Theme.CARD, 16)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        views.forEachIndexed { i, v -> addView(v, params(if (i == 0) 0 else 10)) }
    }

fun Context.field(title: String, control: View): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 13f, Theme.MUTED))
        addView(control, params(4))
    }

fun Context.input(
    hintText: String,
    value: String = "",
    numeric: Boolean = false,
    secret: Boolean = false,
    single: Boolean = true,
    onChange: (String) -> Unit
): EditText = EditText(this).apply {
    hint = hintText
    setText(value)
    setTextColor(Color.WHITE)
    setHintTextColor(Theme.MUTED)
    textSize = 15f
    background = rounded(Theme.BG, 10, Color.parseColor("#3A404B"))
    setPadding(dp(12), dp(12), dp(12), dp(12))
    if (single) setSingleLine() else minLines = 3
    inputType = when {
        numeric -> InputType.TYPE_CLASS_NUMBER
        secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        single -> InputType.TYPE_CLASS_TEXT
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }
    addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { onChange(s.toString()) }
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
    })
}

fun Context.dropdown(items: List<String>, selected: String, onPick: (String) -> Unit): Spinner =
    Spinner(this).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(items.indexOf(selected).coerceAtLeast(0))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                onPick(items[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
