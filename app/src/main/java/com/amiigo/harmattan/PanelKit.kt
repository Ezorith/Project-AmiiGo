package com.amiigo.harmattan

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * Shows one panel at its own size (Home settings → Size): the panel is laid out on a screen 1/k as
 * large and drawn k times bigger, so everything in it (text, icons, spacing) scales together while
 * the other panels and the status bar keep theirs. Touches are mapped back by the view system.
 */
@SuppressLint("ViewConstructor")
class ScaledFrame(ctx: Context, val child: View, val k: Float) : ViewGroup(ctx) {
    init {
        addView(child)
        child.pivotX = 0f; child.pivotY = 0f
        child.scaleX = k; child.scaleY = k
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        child.measure(MeasureSpec.makeMeasureSpec((w / k).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((h / k).toInt(), MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }
}

/** How much bigger [v] is drawn on screen than its own size (its and its parents' scale). */
fun screenScale(v: View): Float {
    var k = 1f
    var p: View? = v
    while (p != null) { k *= p.scaleX; p = p.parent as? View }
    return k
}

fun openHomeSettings(ctx: Context) {
    ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * The "Back to top" button of the Today view, floating at the bottom once the feed is scrolled: the
 * toolbar-sized N9 pill (as Home settings) over a dark gradient so it stands out from the posts behind.
 * Fades in and out; only the pill takes touches.
 */
@SuppressLint("ViewConstructor")
class BackToTopButton(ctx: Context, private val label: String = "Back to top", private val onTap: () -> Unit) : View(ctx) {
    private val text = TextPaint(N9.textPaint(N9.bold, 22f, 0xFFFFFFFF.toInt())) // FONT_BUTTON, as the toolbar
    private val bg = Pill(Pill.Kind.NORMAL)
    private val bgPressed = Pill(Pill.Kind.PRESSED)
    private val shade = android.graphics.Paint()
    private val pill = android.graphics.RectF()
    private var down = false
        set(v) { field = v; invalidate() }
    var shown = false
        private set
    var bottomInset = 0
        set(v) { field = v; requestLayout() }

    init { alpha = 0f; visibility = INVISIBLE }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), N9.ipx(130) + bottomInset)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shade.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x00000000, 0xB3000000.toInt(), 0xE6000000.toInt()), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }

    /** Whether ([x], [y]) in the parent's coordinates is on the pill. */
    fun hits(x: Float, y: Float) = shown && RectF(pill).apply { offset(left.toFloat(), top.toFloat()); inset(-N9.px(12f), -N9.px(8f)) }.contains(x, y)

    fun show(on: Boolean) {
        if (on == shown) return
        shown = on
        animate().cancel()
        if (on) visibility = VISIBLE
        animate().alpha(if (on) 1f else 0f).setDuration(200).setInterpolator(Ease.outExpo)
            .withEndAction { if (!shown) visibility = INVISIBLE }.start()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        val w = maxOf(N9.px(200f), text.measureText(label) + N9.px(48f))
        val h = N9.px(44f)
        val bottom = height - bottomInset - N9.px(18f)
        pill.set(width / 2f - w / 2f, bottom - h, width / 2f + w / 2f, bottom)
        val d = if (down) bgPressed else bg
        d.setBounds(pill.left.toInt(), pill.top.toInt(), pill.right.toInt(), pill.bottom.toInt()); d.draw(canvas)
        text.color = if (down) 0xFF797979.toInt() else 0xFFFFFFFF.toInt()
        val fm = text.fontMetrics
        canvas.drawText(label, pill.centerX() - text.measureText(label) / 2f, pill.centerY() - (fm.ascent + fm.descent) / 2f, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!shown) return false
        val on = RectF(pill).apply { inset(-N9.px(12f), -N9.px(8f)) }.contains(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (!on) return false; down = true }
            MotionEvent.ACTION_MOVE -> down = on
            MotionEvent.ACTION_UP -> { if (down) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onTap() }; down = false }
            MotionEvent.ACTION_CANCEL -> down = false
        }
        return true
    }
}

/** Android's notification panel (or quick settings with [settings]), as a pull on the status bar opens it. */
fun expandShade(ctx: Context, settings: Boolean = false) {
    runCatching {
        val sb = ctx.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod(if (settings) "expandSettingsPanel" else "expandNotificationsPanel").invoke(sb)
    }
}
