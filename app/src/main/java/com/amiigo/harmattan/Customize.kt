package com.amiigo.harmattan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Home settings as a zoomed-out view of the home ring: the three panels side by side (Open apps on
 * the left, Today in the middle, Applications on the right), the centred one's name above it and a
 * Customize button below, in the Harmattan look (N9 pill, page-title type). Swipe to move between
 * panels, tap the middle one to go back to it, tap a side one to bring it to the middle.
 *
 * This view only draws the chrome and takes the touches; [HomeActivity] zooms the real panels.
 */
@SuppressLint("ViewConstructor")
class CustomizeOverlay(
    ctx: Context,
    private val onSwipe: (dir: Int) -> Unit,
    private val onTapCentre: () -> Unit,
    private val onCustomize: () -> Unit,
    private val onAllSettings: () -> Unit,
) : View(ctx) {
    var title = ""
        set(v) { field = v; invalidate() }
    /** The zoomed panels' on-screen rectangles: middle, and one step to each side. */
    val centre = RectF()
    var step = 0f
    var shown = 0f
        set(v) { field = v; invalidate() }

    private val titlePaint = TextPaint(N9.textPaint(N9.bold, 24f, Color.WHITE, scaled = false)) // FONT_PAGE_TITLE
    private val btnText = TextPaint(N9.textPaint(N9.bold, 22f, Color.WHITE, scaled = false))    // FONT_BUTTON
    private val linkText = TextPaint(N9.textPaint(N9.regular, N9.FONT_SMALL, 0xFF8C8C8C.toInt(), scaled = false))
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF3A3A3A.toInt() }
    private val pill = Pill(Pill.Kind.NORMAL)
    private val pillDown = Pill(Pill.Kind.PRESSED)
    private val btn = RectF()
    private val link = RectF()
    private var downOn = 0 // 1 Customize, 2 All settings

    override fun onDraw(c: Canvas) {
        if (shown <= 0f) return
        val a = (shown * 255).toInt()
        // Thin frames round the panels (black on black otherwise), as cards.
        frame.strokeWidth = N9.px(2f)
        frame.alpha = a
        val r = N9.px(14f)
        for (i in -1..1) {
            val f = RectF(centre).apply { offset(i * step, 0f) }
            c.drawRoundRect(f, r, r, frame)
        }
        // Title above the middle panel.
        titlePaint.alpha = a
        val t = title.uppercase()
        val fm = titlePaint.fontMetrics
        val ty = centre.top / 2f + N9.px(12f)
        c.drawText(t, (width - titlePaint.measureText(t)) / 2f, ty - (fm.ascent + fm.descent) / 2f, titlePaint)
        // Customize, the toolbar-sized pill, and the full settings list under it.
        val label = context.getString(R.string.customize)
        val w = maxOf(N9.px(240f), btnText.measureText(label) + N9.px(64f))
        val h = N9.px(52f)
        val top = centre.bottom + (height - centre.bottom - h) / 2f
        btn.set(width / 2f - w / 2f, top, width / 2f + w / 2f, top + h)
        val d = if (downOn == 1) pillDown else pill
        d.alpha = a
        d.setBounds(btn.left.toInt(), btn.top.toInt(), btn.right.toInt(), btn.bottom.toInt()); d.draw(c)
        btnText.color = if (downOn == 1) 0xFF797979.toInt() else Color.WHITE
        btnText.alpha = a
        val bf = btnText.fontMetrics
        c.drawText(label, btn.centerX() - btnText.measureText(label) / 2f, btn.centerY() - (bf.ascent + bf.descent) / 2f, btnText)
    }

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (shown < 0.9f) return shown > 0f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false
                downOn = when {
                    RectF(btn).apply { inset(-N9.px(10f), -N9.px(10f)) }.contains(e.x, e.y) -> 1
                    else -> 0
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (!moved && abs(dx) > N9.px(40f) && abs(dx) > abs(e.y - downY)) {
                    moved = true; downOn = 0; invalidate()
                    onSwipe(if (dx < 0) 1 else -1)
                }
            }
            MotionEvent.ACTION_UP -> {
                val on = downOn
                downOn = 0; invalidate()
                if (moved) return true
                when {
                    on == 1 -> { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onCustomize() }
                    centre.contains(e.x, e.y) -> onTapCentre()
                    e.x < centre.left -> onSwipe(-1)
                    e.x > centre.right -> onSwipe(1)
                }
            }
            MotionEvent.ACTION_CANCEL -> { downOn = 0; invalidate() }
        }
        return true
    }
}
