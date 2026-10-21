package app.ferry

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

// Tiny view helpers: the app has five screens, so plain framework views beat a UI toolkit dependency.

private val io = Executors.newCachedThreadPool()

/** Runs [work] off the main thread, then [done] on it with the result or the error. */
fun <T> Activity.bg(work: () -> T, done: (Result<T>) -> Unit) {
    io.execute {
        val r = runCatching(work)
        runOnUiThread { if (!isDestroyed) done(r) }
    }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.color(id: Int) = getColor(id)

fun Context.column(pad: Int = 20) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.row() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Activity.screen(body: LinearLayout) {
    // Insets go on the ScrollView: fitsSystemWindows replaces a view's own padding, so the body keeps its margins.
    setContentView(ScrollView(this).apply { fitsSystemWindows = true; isFillViewport = true; addView(body) })
}

fun Context.text(s: String, sp: Float = 15f, color: Int = R.color.text, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color(color))
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.title(s: String) = text(s, 26f, bold = true).apply { letterSpacing = -0.02f; setPadding(0, dp(8), 0, dp(8)) }

fun Context.label(s: String) = text(s.uppercase(), 12f, R.color.muted, bold = true).apply {
    letterSpacing = 0.04f
    setPadding(0, dp(20), 0, dp(8))
}

fun Context.wordmark() = row().apply {
    addView(ImageView(context).apply {
        setImageResource(R.mipmap.ic_launcher)
        contentDescription = null
    }, LinearLayout.LayoutParams(dp(36), dp(36)))
    addView(text("ferry", 28f, bold = true).apply {
        letterSpacing = -0.04f
        setPadding(dp(10), 0, 0, 0)
    })
}

/** Square-cornered button, primary (filled) or secondary (outlined). */
fun Context.button(label: String, primary: Boolean = true, onClick: (View) -> Unit) = Button(this).apply {
    text = label
    isAllCaps = false
    textSize = 15f
    typeface = Typeface.DEFAULT_BOLD
    minHeight = dp(52)
    stateListAnimator = null
    background = if (primary) ColorDrawable(color(R.color.accent)) else box(R.color.surface, R.color.line)
    setTextColor(color(if (primary) R.color.on_accent else R.color.text))
    setOnClickListener(onClick)
}

fun Context.box(fill: Int, stroke: Int) = GradientDrawable().apply {
    setColor(color(fill))
    setStroke(dp(1), color(stroke))
    cornerRadius = 0f
}

fun Context.card() = column(0).apply { background = box(R.color.surface, R.color.line) }

fun Context.field(hint: String, value: String = "", numeric: Boolean = false) = EditText(this).apply {
    this.hint = hint
    setText(value)
    textSize = 16f
    setTextColor(color(R.color.text))
    setHintTextColor(color(R.color.muted))
    background = box(R.color.surface, R.color.line)
    setPadding(dp(12), 0, dp(12), 0)
    minHeight = dp(48)
    isSingleLine = true
    inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
}

/** One list row: title + subtitle, with an optional trailing view. */
fun Context.item(name: String, sub: String, subColor: Int = R.color.muted, trailing: View? = null) = row().apply {
    setPadding(dp(16), dp(12), dp(16), dp(12))
    minimumHeight = dp(56)
    addView(column(0).apply {
        addView(text(name, 15f).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        addView(text(sub, 12f, subColor))
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    trailing?.let { addView(it) }
}

fun Context.check(checked: Boolean) = CheckBox(this).apply {
    isChecked = checked
    buttonTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
}

fun Context.divider() = View(this).apply {
    setBackgroundColor(color(R.color.line))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
}

fun LinearLayout.gap(dp: Int) = addView(View(context), LinearLayout.LayoutParams(1, context.dp(dp)))
