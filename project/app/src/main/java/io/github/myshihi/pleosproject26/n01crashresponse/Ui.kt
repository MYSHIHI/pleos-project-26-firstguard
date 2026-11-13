package io.github.myshihi.pleosproject26.n01crashresponse

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object C {
    const val BG = 0xFF0D1015.toInt()
    const val PANEL = 0xFF151A21.toInt()
    const val PANEL2 = 0xFF1D242E.toInt()
    const val LINE = 0xFF2B3440.toInt()
    const val TEXT = 0xFFEFF2F6.toInt()
    const val MUTED = 0xFF8D98A8.toInt()
    const val RED = 0xFFFF5A4E.toInt()
    const val AMBER = 0xFFFFB648.toInt()
    const val GREEN = 0xFF3FD99A.toInt()
    const val BLUE = 0xFF5FAEFF.toInt()
    const val VIOLET = 0xFFB89EFF.toInt()

    fun alpha(color: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun level(l: Level) = when (l) { Level.URGENT -> RED; Level.CAUTION -> AMBER; Level.CHECK -> BLUE; Level.MINOR -> GREEN }
    fun source(s: Source) = when (s) { Source.VEHICLE -> BLUE; Source.SAMPLE -> AMBER; Source.USER -> GREEN; Source.APP -> MUTED; Source.MOCK -> VIOLET }
    fun person(p: PersonState) = when (p) {
        PersonState.UNCHECKED -> MUTED; PersonState.PRESENT -> AMBER; PersonState.NOT_ON_BOARD -> MUTED; PersonState.ANSWERED_OK -> GREEN
        PersonState.NEEDS_HELP, PersonState.NO_RESPONSE -> RED; PersonState.EVACUATED -> BLUE
    }
}

/** Layout is designed on a 1600dp-wide canvas and scaled to the actual screen width (Pleos Connect is 2560 x 1440 @160dpi). */
class Ui(private val ctx: Context) {
    private val metrics = ctx.resources.displayMetrics
    // Use the app window width (Pleos shows apps beside the vehicle panel), not the full display.
    val scale: Float = (ctx.resources.configuration.screenWidthDp / 1600f).coerceIn(0.75f, 1.6f)
    fun dp(v: Int) = (v * metrics.density * scale).toInt()
    fun dpf(v: Float) = v * metrics.density * scale

    fun bg(color: Int, radius: Int = 18, stroke: Int? = null, strokeWidth: Int = 2) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radius.toFloat())
        if (stroke != null) setStroke(dp(strokeWidth), stroke)
    }

    fun text(s: CharSequence, size: Float = 20f, color: Int = C.TEXT, bold: Boolean = false, lines: Int = 0) = TextView(ctx).apply {
        text = s; textSize = size * scale; setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.12f)
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }

    fun button(label: CharSequence, fill: Int, fg: Int = C.BG, size: Float = 20f, outline: Boolean = false, enabled: Boolean = true,
               onClick: () -> Unit): TextView = text(label, size, if (outline) fill else fg, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(64)
        setPadding(dp(22), dp(12), dp(22), dp(12))
        val base = if (outline) bg(C.alpha(fill, 0.08f), 16, fill, 2) else bg(fill, 16)
        background = RippleDrawable(ColorStateList.valueOf(C.alpha(Color.WHITE, 0.25f)), base, null)
        isClickable = enabled; isFocusable = enabled
        alpha = if (enabled) 1f else 0.38f
        if (enabled) setOnClickListener { onClick() }
    }

    fun chip(label: CharSequence, color: Int, size: Float = 16f, filled: Boolean = false) = text(label, size, if (filled) C.BG else color, bold = true).apply {
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = if (filled) bg(color, 40) else bg(C.alpha(color, 0.14f), 40, C.alpha(color, 0.55f), 1)
    }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL, vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; this.gravity = gravity; views.forEach { addView(it) }
    }

    fun column(vararg views: View) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; views.forEach { addView(it) } }

    fun card(color: Int = C.PANEL, padding: Int = 24, stroke: Int? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(color, 22, stroke, 2)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun space(w: Int = 0, h: Int = 0) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(w), dp(h)) }

    fun weight(v: View, w: Float = 1f, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
        v.layoutParams = LinearLayout.LayoutParams(0, height, w); return v
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
           top: Int = 0, end: Int = 0, bottom: Int = 0, start: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }

    fun frame() = FrameLayout(ctx)
}

/** Simple flow layout for chips that wrap onto several lines. */
class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + lineH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}
