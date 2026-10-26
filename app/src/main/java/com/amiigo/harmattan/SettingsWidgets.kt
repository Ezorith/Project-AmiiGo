package com.amiigo.harmattan

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Settings controls drawn in the Harmattan style: choice rows, picture choices, the home preview, app grids. */

private const val ACCENT = N9.COLOR_ACCENT1
private const val TRACK = 0xFF2E2E2E.toInt()
private const val DIM = 0xFF8C8C8C.toInt()

/** A row of equal segments, one chosen (the N9 button group: the checked one is blue). */
@SuppressLint("ViewConstructor")
class Segments(ctx: Context, private val labels: List<String>, var selected: Int, private val fit: Boolean = false, private val onPick: (Int) -> Unit) : View(ctx) {
    private val text = TextPaint(N9.textPaint(N9.regular, N9.FONT_XSMALL, Color.WHITE, scaled = false))
    private val bold = TextPaint(N9.textPaint(N9.bold, N9.FONT_XSMALL, Color.WHITE, scaled = false))
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private fun widthOf(i: Int) = maxOf(N9.px(72f), bold.measureText(labels[i]) + N9.px(36f))
    private val gapPx get() = N9.px(4f)

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(if (fit) (labels.indices.sumOf { widthOf(it).toDouble() } + gapPx * (labels.size - 1)).toInt() else MeasureSpec.getSize(w), N9.ipx(48))

    private fun left(i: Int): Float = if (fit) (0 until i).sumOf { widthOf(it).toDouble() }.toFloat() + gapPx * i else i * ((width - gapPx * (labels.size - 1)) / labels.size + gapPx)
    private fun segW(i: Int): Float = if (fit) widthOf(i) else (width - gapPx * (labels.size - 1)) / labels.size

    override fun onDraw(c: Canvas) {
        val n = labels.size
        val gap = gapPx
        val sw = (width - gap * (n - 1)) / n
        val rad = N9.px(8f)
        for (i in 0 until n) {
            val x = left(i)
            val w = segW(i)
            r.set(x, N9.px(4f), x + w, height - N9.px(4f))
            if (i == selected) { fill.color = 0xFF057CE3.toInt(); c.drawRoundRect(r, rad, rad, fill) }
            val p = if (i == selected) bold else text
            val tw = p.measureText(labels[i])
            val fm = p.fontMetrics
            c.drawText(labels[i], x + (w - tw) / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = labels.indices.firstOrNull { e.x < left(it) + segW(it) + gapPx / 2f } ?: (labels.size - 1)
            if (i != selected) { selected = i; invalidate(); performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onPick(i) }
        }
        return true
    }
}

/**
 * A slider that stops at fixed steps (the N9 slider: blue groove up to a white handle), with a
 * small and a large sample at its ends. [onMove] follows the finger, [onPick] fires on release.
 */
@SuppressLint("ViewConstructor")
class StepSlider(
    ctx: Context, private val steps: Int, var selected: Int, private val glyph: Glyph,
    private val onMove: (Int) -> Unit, private val onPick: (Int) -> Unit,
) : View(ctx) {
    enum class Glyph { TEXT, ICON }

    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3A.toInt(); strokeCap = Paint.Cap.ROUND; strokeWidth = N9.px(6f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeCap = Paint.Cap.ROUND; strokeWidth = N9.px(6f) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.STROKE; strokeWidth = N9.px(3f) }
    private val small = TextPaint(N9.textPaint(N9.regular, 18f, Color.WHITE, scaled = false))
    private val large = TextPaint(N9.textPaint(N9.regular, 32f, Color.WHITE, scaled = false))
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = N9.px(2.5f) }
    private val side = N9.px(52f)
    private val grooveArt = NineSlice(N9.bitmap(ctx, R.drawable.n9_slider_groove), 4, 4, 0, 0)
    private val elapsedArt = NineSlice(N9.bitmap(ctx, R.drawable.n9_slider_elapsed), 4, 4, 0, 0)
    private val handleUp = N9.bitmap(ctx, R.drawable.n9_slider_handle)
    private val handleDown = N9.bitmap(ctx, R.drawable.n9_slider_handle_pressed)
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var dragging = false

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), N9.ipx(64))

    private val trackL get() = side + N9.px(14f)
    private val trackR get() = width - side - N9.px(14f)
    private fun xOf(i: Int) = trackL + (trackR - trackL) * i / (steps - 1).coerceAtLeast(1)

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        // Ends: a small and a large sample of what the slider sizes.
        when (glyph) {
            Glyph.TEXT -> {
                val fm1 = small.fontMetrics; val fm2 = large.fontMetrics
                c.drawText("A", (side - small.measureText("A")) / 2f, cy - (fm1.ascent + fm1.descent) / 2f, small)
                c.drawText("A", width - side + (side - large.measureText("A")) / 2f, cy - (fm2.ascent + fm2.descent) / 2f, large)
            }
            Glyph.ICON -> {
                val a = N9.px(18f); val b = N9.px(34f)
                c.drawRoundRect(RectF((side - a) / 2f, cy - a / 2f, (side + a) / 2f, cy + a / 2f), a * 0.28f, a * 0.28f, outline)
                val l = width - side + (side - b) / 2f
                c.drawRoundRect(RectF(l, cy - b / 2f, l + b, cy + b / 2f), b * 0.28f, b * 0.28f, outline)
            }
        }
        val gh = N9.px(8f)
        val hx = xOf(selected)
        grooveArt.setBounds(trackL.toInt(), (cy - gh / 2f).toInt(), trackR.toInt(), (cy + gh / 2f).toInt()); grooveArt.draw(c)
        elapsedArt.setBounds(trackL.toInt(), (cy - gh / 2f).toInt(), hx.toInt().coerceAtLeast(trackL.toInt() + 1), (cy + gh / 2f).toInt()); elapsedArt.draw(c)
        val hs = N9.px(64f)
        c.drawBitmap(if (dragging) handleDown else handleUp, null, RectF(hx - hs / 2f, cy - hs / 2f, hx + hs / 2f, cy + hs / 2f), artPaint)
    }

    private var startIdx = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = ((e.x - trackL) / (trackR - trackL) * (steps - 1)).let { Math.round(it) }.coerceIn(0, steps - 1)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; invalidate(); startIdx = selected; parent?.requestDisallowInterceptTouchEvent(true); step(i) }
            MotionEvent.ACTION_MOVE -> step(i)
            MotionEvent.ACTION_UP -> { dragging = false; invalidate(); step(i); if (selected != startIdx) onPick(selected) }
            MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate(); if (selected != startIdx) onPick(selected) }
        }
        return true
    }

    private fun step(i: Int) {
        if (i == selected) return
        selected = i
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
        onMove(i)
    }
}

/** One picture choice: its icons (filled in when ready) and a name. */
class Choice(val id: String, val label: String, var icons: List<Bitmap> = emptyList())

/**
 * Picture choices side by side, scrolling sideways when they don't fit: each shows how the icons
 * will look with that option, the chosen one outlined in blue.
 */
@SuppressLint("ViewConstructor")
class ChoiceStrip(ctx: Context, val choices: List<Choice>, var selected: String, private val iconsPer: Int, private val onPick: (String) -> Unit) : View(ctx) {
    private val label = TextPaint(N9.textPaint(N9.regular, N9.FONT_XXSMALL, DIM, scaled = false))
    private val labelOn = TextPaint(N9.textPaint(N9.bold, N9.FONT_XXSMALL, Color.WHITE, scaled = false))
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = N9.px(3f); color = ACCENT }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF161616.toInt() }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val icon = N9.px(56f)
    private val pad = N9.px(10f)
    private val gap = N9.px(10f)
    private val tileW = pad * 2 + iconsPer * icon + (iconsPer - 1) * N9.px(6f)
    private val tileH = pad * 2 + icon + N9.px(30f)

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension((choices.size * tileW + (choices.size - 1) * gap).toInt(), tileH.toInt())

    override fun onDraw(c: Canvas) {
        val rad = N9.px(10f)
        choices.forEachIndexed { i, ch ->
            val x = i * (tileW + gap)
            r.set(x, 0f, x + tileW, tileH)
            c.drawRoundRect(r, rad, rad, bg)
            if (ch.id == selected) { r.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2); c.drawRoundRect(r, rad, rad, stroke) }
            ch.icons.take(iconsPer).forEachIndexed { j, b ->
                val ix = x + pad + j * (icon + N9.px(6f))
                c.drawBitmap(b, null, RectF(ix, pad, ix + icon, pad + icon), bmpPaint)
            }
            val p = if (ch.id == selected) labelOn else label
            val t = TextUtils.ellipsize(ch.label, p, tileW - N9.px(8f), TextUtils.TruncateAt.END).toString()
            c.drawText(t, x + (tileW - p.measureText(t)) / 2f, tileH - N9.px(10f), p)
        }
    }

    private var downX = 0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = e.x
            MotionEvent.ACTION_UP -> if (kotlin.math.abs(e.x - downX) < N9.px(12f)) {
                val i = floor(e.x / (tileW + gap)).toInt()
                val ch = choices.getOrNull(i) ?: return true
                if (e.x - i * (tileW + gap) <= tileW && ch.id != selected) {
                    selected = ch.id; invalidate()
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onPick(ch.id)
                }
            }
        }
        return true
    }
}

/**
 * A small picture of the Applications view with the chosen interface scale and icon size, laid out
 * with the launcher's own measures (SwipeLauncherButtonStyle 112×118, 2px/30px spacing).
 */
class HomePreview(ctx: Context) : PanelPreview(ctx) {
    var uiScale = 1f; set(v) { field = v; invalidate() }
    var iconScale = 1f; set(v) { field = v; invalidate() }
    var textScale = 1f; set(v) { field = v; invalidate() }
    var icons: List<Pair<Bitmap, String>> = emptyList(); set(v) { field = v; invalidate() }

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = N9.regular; color = Color.WHITE }

    override fun drawPanel(c: Canvas, u0: Float) {
        // The Applications view at its Scaling: the screen is 480/scaling N9 px wide.
        val n9W = 480f / uiScale
        val u = width / n9W
        val gm = LauncherGrid.metrics(1f, iconScale, textScale)
        val bw = gm.bw; val bh = gm.bh; val hs = gm.hs; val vs = gm.vs
        val cols = max(3, ((n9W - 2 * gm.padL + hs) / (bw + hs)).toInt())
        val padL = (n9W - (cols * bw + (cols - 1) * hs)) / 2f
        val iconN9 = gm.icon
        text.textSize = N9.FONT_XSMALL * u * textScale
        val gridTop = gm.padT * u
        icons.forEachIndexed { i, (b, name) ->
            val col = i % cols; val row = i / cols
            val x = (padL + col * (bw + hs)) * u
            val y = gridTop + row * (bh + vs) * u
            if (y > height) return@forEachIndexed
            val isz = iconN9 * u
            val ix = x + (bw * u - isz) / 2f
            val iy = y + (gm.iconBottom - iconN9) * u
            c.drawBitmap(b, null, RectF(ix, iy, ix + isz, iy + isz), bmpPaint)
            val label = TextUtils.ellipsize(name, text, bw * u, TextUtils.TruncateAt.END).toString()
            c.drawText(label, x + (bw * u - text.measureText(label)) / 2f, y + (gm.iconBottom + 8f) * u - text.ascent(), text)
        }
    }
}

/** A grid cell in the app picker: the app's icon and name, with a tick when chosen. */
private class AppCell(ctx: Context) : View(ctx) {
    var entry: AppEntry? = null
    var chosen = false
    var dimChosen = false
    private val text = TextPaint(N9.textPaint(N9.regular, N9.FONT_XSMALL, Color.WHITE, scaled = false))
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val tickLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = N9.px(3.5f)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), N9.ipx(LauncherGrid.BH + 18))

    override fun onDraw(c: Canvas) {
        val e = entry ?: return
        val isz = N9.px(72f)
        val ix = (width - isz) / 2f
        val iy = N9.px(10f)
        bmpPaint.alpha = if (chosen && dimChosen) 110 else 255
        c.drawBitmap(e.icon, null, RectF(ix, iy, ix + isz, iy + isz), bmpPaint)
        val label = TextUtils.ellipsize(e.label, text, width - N9.px(8f), TextUtils.TruncateAt.END).toString()
        text.alpha = if (chosen && dimChosen) 140 else 255
        c.drawText(label, (width - text.measureText(label)) / 2f, iy + isz + N9.px(8f) - text.ascent(), text)
        if (chosen) {
            val r = N9.px(15f)
            val cx = ix + isz - N9.px(4f); val cy = iy + N9.px(4f)
            c.drawCircle(cx, cy, r + N9.px(2.5f), ring)
            c.drawCircle(cx, cy, r, tick)
            val p = Path().apply {
                moveTo(cx - r * 0.45f, cy + r * 0.02f); lineTo(cx - r * 0.1f, cy + r * 0.38f); lineTo(cx + r * 0.48f, cy - r * 0.32f)
            }
            c.drawPath(p, tickLine)
        }
    }
}

/**
 * Choose apps from an icon grid like the Applications view. [keyOf] says what a choice is stored
 * as (the app's key, or its package); [dim] greys chosen apps (hidden apps).
 */
fun pickApps(
    ctx: Context, title: String, help: String, apps: List<AppEntry>, chosen: Set<String>,
    keyOf: (AppEntry) -> String, dim: Boolean, done: (Set<String>) -> Unit,
) {
    val sel = chosen.toMutableSet()
    val d = Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar)
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
    fun tv(s: String, size: Float, color: Int, face: android.graphics.Typeface) = TextView(ctx).apply {
        text = s; setTextColor(color); typeface = face
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, N9.px(size))
    }
    val count = tv("", N9.FONT_XSMALL, N9.COLOR_INVERTED_SECONDARY_FOREGROUND, N9.regular)
    val shown = apps.map(keyOf).toSet()
    fun updateCount() { val n = sel.count { it in shown }; count.text = if (n == 0) help else "$n chosen · $help" }
    updateCount()
    root.addView(tv(title.uppercase(), 24f, Color.WHITE, N9.bold).apply { setPadding(N9.ipx(16), N9.ipx(24), N9.ipx(16), N9.ipx(4)) })
    root.addView(count.apply { setPadding(N9.ipx(16), 0, N9.ipx(16), N9.ipx(12)) })

    val grid = GridView(ctx).apply {
        numColumns = max(3, min(6, (N9.widthN9 / LauncherGrid.BW).toInt()))
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        setPadding(N9.ipx(8), 0, N9.ipx(8), 0)
        clipToPadding = false
        adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int) = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val cell = (convert as? AppCell) ?: AppCell(ctx)
                cell.entry = apps[p]; cell.chosen = keyOf(apps[p]) in sel; cell.dimChosen = dim
                cell.invalidate()
                return cell
            }
        }
        setOnItemClickListener { _, v, p, _ ->
            val k = keyOf(apps[p])
            if (!sel.add(k)) sel.remove(k)
            (v as AppCell).chosen = k in sel; v.invalidate()
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            updateCount()
        }
    }
    root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

    // Bottom bar: Cancel / Done side by side, the same toolbar as the switcher's Done / Close all.
    val toolbar = EditToolbar(ctx, listOf("Cancel" to { d.dismiss() }, "Done" to { d.dismiss(); done(sel) }), inRow = true)
    root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
    toolbar.post { toolbar.show(true) }

    root.setOnApplyWindowInsetsListener { v, insets ->
        @Suppress("DEPRECATION")
        v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets
    }
    d.setContentView(root)
    d.window?.apply {
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
    }
    d.show()
}


/** The meegotouch switch (66x42 track, 30px thumb), inverted: blue track when on. */
@SuppressLint("ViewConstructor")
class N9Switch(ctx: Context, var on: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
    private val trackOn = N9.bitmap(ctx, R.drawable.n9_switch_on)
    private val trackOff = N9.bitmap(ctx, R.drawable.n9_switch_off)
    private val thumb = N9.bitmap(ctx, R.drawable.n9_switch_thumb)
    private val thumbDown = N9.bitmap(ctx, R.drawable.n9_switch_thumb_pressed)
    private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var t = if (on) 1f else 0f
    private var down = false

    fun flip() {
        on = !on
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        android.animation.ValueAnimator.ofFloat(t, if (on) 1f else 0f).apply {
            duration = 150; interpolator = Ease.outExpo
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { onChange(on) }
            })
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        p.alpha = 255
        c.drawBitmap(trackOff, null, r, p)
        p.alpha = (t * 255).toInt()
        c.drawBitmap(trackOn, null, r, p)
        p.alpha = 255
        val k = width / 66f
        val ts = 30f * k
        val x = (6f + t * (66f - 12f - 30f)) * k
        val y = (height - ts) / 2f
        c.drawBitmap(if (down) thumbDown else thumb, null, RectF(x, y, x + ts, y + ts), p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
            MotionEvent.ACTION_UP -> { down = false; invalidate(); flip() }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }
}
