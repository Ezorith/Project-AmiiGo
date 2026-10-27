package com.amiigo.harmattan

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The open-applications view (SwipeSwitcherView), meegotouchhome.css portrait values:
 *  - DetailSwitcher: 2 columns, SwipeDetailviewButton 21.6mm x 36.8mm,
 *    SwitcherDetailviewPolicy margins 1.4/1.4/2.4/1.4mm, spacing 2.0mm
 *  - OverviewSwitcher: 3 columns, SwipeOverviewButton 13.8mm x 23.5mm,
 *    SwitcherOverviewPolicy margins 1.4/1.4/2.4/1.4mm, spacing 1.9mm
 *  - SwipeSwitcherButtonStyle: cropping-top HEIGHT_STATUSBAR, corner-radius 0.6mm, brightness 1.08,
 *    edit-mode-brightness 0.5, edit-mode-animation 250ms outexpo, outgoing 250ms inback (overshoot 2)
 *    to scale 0.5, incoming 400ms overshotbezier
 *  - SwipeSwitcherStyle: relayout-transition-base-delay 250, -delay-increment 20, button-transition-duration 250
 *  - MLabelStyle#SwipeSwitcherEmptyState: FONT_FAMILY_LIGHT 6.2mm, #323232, centred, 86% high
 *
 * Each tile shows the app as a window card (see [WindowArt.drawCard]) — on the N9 it was the live window.
 */
class SwitcherPanel(ctx: Context) : FrameLayout(ctx) {
    val pannable = PannableView(ctx)
    val grid = SwitcherGrid(ctx, this)
    private val emptyPaint = N9.textPaint(N9.light, 62f, 0xFF323232.toInt())
    private var emptyAlpha = 1f
    private var emptyAnim: ValueAnimator? = null
    var visibleNow = false
        set(v) { field = v; if (v) { grid.playIncoming(); refreshPreviews() } }

    /** New snapshots for the tiles (live previews), then redraw them. */
    fun refreshPreviews() = Previews.refresh(Apps.recentApps()) { grid.invalidateTiles() }
    /** Tap on a tile: (entry, tile). */
    var onOpen: ((AppEntry, View) -> Unit)? = null

    // Edit mode's toolbar, as on the N9: Done, and Close all.
    private val toolbar = EditToolbar(ctx, listOf(
        ctx.getString(R.string.done) to { exitEditMode() },
        ctx.getString(R.string.qtn_home_recents_close_all) to { grid.closeAll() },
    ), inRow = true)

    init {
        setWillNotDraw(false)
        pannable.content = grid
        addView(pannable, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        Apps.addRecentListener { grid.sync(); refreshPreviews() }
        grid.sync()
    }

    var bottomInset = 0
        set(v) { field = v; toolbar.bottomInset = v; updateInset() }

    private fun updateInset() { pannable.bottomInset = bottomInset + if (grid.editMode) N9.ipx(toolbar.barHeightN9) else 0 }

    /** Edit mode (held): close badges on the tiles, and the toolbar with Home settings. */
    fun onEditModeChanged(on: Boolean) { toolbar.show(on); updateInset() }

    fun exitEditMode() = grid.setEditMode(false)
    val inEditMode get() = grid.editMode
    fun tileFor(e: AppEntry): SwitcherTile? = grid.tileFor(e)

    /** empty-state-label-animation: 250ms outexpo. */
    fun setEmpty(empty: Boolean) {
        val to = if (empty) 1f else 0f
        if (emptyAlpha == to) return
        emptyAnim?.cancel()
        emptyAnim = ValueAnimator.ofFloat(emptyAlpha, to).apply {
            duration = 250; interpolator = Ease.outExpo
            addUpdateListener { emptyAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (emptyAlpha <= 0f) return
        val text = context.getString(R.string.qtn_home_recents_empty)
        // The N9's dark grey on black; on a wallpaper a light see-through grey, so it can be read.
        val onWall = Wallpaper.exists(context)
        emptyPaint.color = if (onWall) 0xFFFFFFFF.toInt() else 0xFF323232.toInt()
        emptyPaint.alpha = (emptyAlpha * (if (onWall) 0.55f else 1f) * 255).toInt()
        // Word-wrapped and centred in a 100% x 86% box, like the MLabel.
        val w = width - N9.ipx(28f)
        val tp = TextPaint(emptyPaint)
        val l = android.text.StaticLayout.Builder.obtain(text, 0, text.length, tp, w.coerceAtLeast(1))
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        val boxH = (height - bottomInset) * 0.86f
        canvas.save()
        canvas.translate(N9.px(14f), (boxH - l.height) / 2f)
        l.draw(canvas)
        canvas.restore()
    }
}

@SuppressLint("ViewConstructor")
class SwitcherTile(ctx: Context, var entry: AppEntry) : View(ctx) {
    var dim = 0f                    // 0 = normal, 0.5 = edit-mode-brightness
        set(v) { field = v; invalidate() }
    var badge = 0f
    var badgeAlpha = 0f
    /** Size in device pixels, set by the grid. */
    var pxW = 0
    var pxH = 0
    private val rect = RectF()
    private val dimPaint = Paint().apply { color = Color.BLACK }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, N9.px(6f)) // corner-radius 0.6mm
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(pxW, pxH)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        // The app as you left it (Android's snapshot, via Shizuku), else its window card.
        val snap = Previews.get(entry)
        if (snap != null) Previews.draw(canvas, rect, snap) else WindowArt.drawCard(canvas, rect, entry)
        if (dim > 0f) { dimPaint.alpha = (dim * 255).toInt(); canvas.drawRect(rect, dimPaint) }
    }
}

@SuppressLint("ViewConstructor")
class SwitcherGrid(ctx: Context, private val panel: SwitcherPanel) : ViewGroup(ctx) {
    private class Mode(val cols: Int, val w: Float, val h: Float, val spacingH: Float, val spacingV: Float)
    companion object {
        private val DETAIL = Mode(2, 216f, 368f, 20f, 20f)
        private val OVERVIEW = Mode(3, 138f, 235f, 19f, 19f)
        const val M_L = 14f
        const val M_T = 24f
        const val M_B = 14f
        const val BADGE = 48f       // SwipeOverviewButtonCloseButton 4.8mm, margin -0.8mm
        const val BADGE_MARGIN = -8f
    }

    private val tiles = ArrayList<SwitcherTile>()
    private var userMode: Mode? = null
    private var userModeCount = -1
    var editMode = false
        private set
    private val closeBmp = N9.bitmap(ctx, R.drawable.n9_close_thumbnail)
    private val badgePaint = Apps.iconPaint()
    private val pendingIncoming = HashSet<String>()
    private var laidOut = false

    private val mode: Mode
        get() {
            val u = userMode
            if (u != null && userModeCount == tiles.size) return u
            userMode = null
            return if (tiles.size <= Apps.switcherLargeMax) DETAIL else OVERVIEW
        }
    private var lastMode: Mode? = null

    init {
        clipChildren = false
        setWillNotDraw(false)
    }

    private val showEmpty = Runnable { panel.setEmpty(tiles.isEmpty()) }

    fun tileFor(e: AppEntry) = tiles.firstOrNull { it.entry.key == e.key }

    fun invalidateTiles() { for (t in tiles) t.invalidate() }

    /** A tile that the close animation will land in shouldn't also play the incoming animation. */
    fun skipIncoming(e: AppEntry) { pendingIncoming.remove(e.key) }


    fun sync() {
        val apps = Apps.recentApps()
        // A tile on its way out is never reused, even if a stale list still names its app.
        val byKey = tiles.filter { it.tag != "closing" }.associateBy { it.entry.key }
        val next = apps.map { e ->
            byKey[e.key]?.also { it.entry = e } ?: SwitcherTile(context, e).also {
                addView(it)
                if (laidOut) pendingIncoming += e.key
            }
        }
        val keep = next.toSet()
        for (t in tiles) if (t !in keep && t.tag != "closing") removeView(t)
        tiles.clear(); tiles += next
        // "Nothing open yet" waits until the last tiles have finished shrinking away.
        removeCallbacks(showEmpty)
        val closing = (0 until childCount).count { getChildAt(it).tag == "closing" }
        if (tiles.isEmpty() && closing > 0) postDelayed(showEmpty, 100L + 20L * closing + 250L)
        else panel.setEmpty(tiles.isEmpty())
        if (tiles.isEmpty() && editMode) setEditMode(false)
        if (panel.visibleNow) post { playIncoming() }
        requestLayout()
    }

    /**
     * The switcher keeps the N9 proportions whatever the interface scale: its 480-px-wide layout
     * always spans the screen width, so this is device pixels per N9 pixel for the tile grid.
     */
    private var unit = N9.s
    private fun u(v: Float) = v * unit

    private fun slot(i: Int, m: Mode): RectF {
        val col = i % m.cols; val row = i / m.cols
        val x = u(M_L + col * (m.w + m.spacingH))
        val y = u(M_T + row * (m.h + m.spacingV))
        return RectF(x, y, x + u(m.w), y + u(m.h))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0) unit = w / 480f
        val m = mode
        for (t in tiles) { t.pxW = u(m.w).toInt(); t.pxH = u(m.h).toInt(); t.measure(0, 0) }
        val rows = ceil(tiles.size / m.cols.toFloat()).toInt()
        val h = if (rows == 0) 0f else u(M_T + rows * m.h + (rows - 1) * m.spacingV + M_B)
        // While tiles are still closing or sliding up, keep the old height: a shorter grid would cut
        // off the tiles still drawn in its last row.
        // At least the visible area, so holding the empty space below the tiles reaches the grid too.
        val viewport = panel.pannable.height - panel.pannable.bottomInset
        setMeasuredDimension(w, maxOf(h.toInt(), holdHeight, viewport))
    }

    private var relayoutDelay = 0L

    /** The next layout puts tiles straight into place (home is hidden; nothing to animate). */
    var placeInstantly = false

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val m = mode
        val modeChanged = lastMode != null && lastMode !== m
        for ((i, tile) in tiles.withIndex()) {
            val s = slot(i, m)
            val oldL = tile.left + tile.translationX
            val oldT = tile.top + tile.translationY
            val oldW = tile.width * tile.scaleX
            val placed = tile.isLaidOut
            tile.layout(s.left.toInt(), s.top.toInt(), s.right.toInt(), s.bottom.toInt())
            if (placeInstantly) {
                tile.animate().cancel()
                tile.translationX = 0f; tile.translationY = 0f; tile.scaleX = 1f; tile.scaleY = 1f
                continue
            }
            if (placed && laidOut && (oldL != s.left || oldT != s.top || modeChanged)) {
                // Relayout transition; pivot top-left so size changes read as a reflow.
                tile.pivotX = 0f; tile.pivotY = 0f
                val sc = if (tile.width > 0) oldW / tile.width else 1f
                tile.translationX = oldL - s.left; tile.translationY = oldT - s.top
                tile.scaleX = sc; tile.scaleY = sc
                val delay = if (modeChanged) 0L else relayoutDelay + 20L * i
                tile.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(delay).setDuration(250).setInterpolator(Ease.outExpo)
                    .setUpdateListener { invalidate() }.start()
            }
        }
        relayoutDelay = 0L
        placeInstantly = false
        lastMode = m
        laidOut = true
    }

    fun playIncoming() {
        if (pendingIncoming.isEmpty()) return
        for (t in tiles) {
            if (t.entry.key !in pendingIncoming) continue
            t.animate().cancel()
            t.pivotX = t.width / 2f; t.pivotY = t.height / 2f
            t.scaleX = 0.5f; t.scaleY = 0.5f; t.alpha = 0f
            t.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0).setDuration(400)
                .setInterpolator(Ease.overshotBezier).setUpdateListener { invalidate() }.start()
        }
        pendingIncoming.clear()
    }

    fun setEditMode(on: Boolean) {
        if (editMode == on) return
        editMode = on
        for (t in tiles) animateEdit(t, on)
        panel.onEditModeChanged(on)
    }

    private fun animateEdit(t: SwitcherTile, on: Boolean) {
        val fromDim = t.dim; val fromB = t.badge; val fromA = t.badgeAlpha
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (on) 400 else 250
            addUpdateListener {
                val f = it.animatedFraction
                // Brightness follows edit-mode-animation (250ms outexpo); the badge pops in over 400ms.
                val e = Ease.outExpo.getInterpolation(min(1f, f * (if (on) 400f / 250f else 1f)))
                t.dim = fromDim + ((if (on) 0.5f else 0f) - fromDim) * e
                val bs = if (on) Ease.overshotBezier.getInterpolation(f) else Ease.outExpo.getInterpolation(f)
                t.badge = fromB + ((if (on) 1f else 0f) - fromB) * bs
                t.badgeAlpha = fromA + ((if (on) 1f else 0f) - fromA) * Ease.outExpo.getInterpolation(f)
                invalidate()
            }
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val dst = RectF()
        for (t in tiles) {
            if (t.badge <= 0.001f || t.badgeAlpha <= 0.001f) continue
            val right = t.left + t.translationX + t.width * t.scaleX
            val top = t.top + t.translationY
            val size = N9.px(BADGE)
            val cx = right - size / 2f - N9.px(BADGE_MARGIN)
            val cy = top + size / 2f + N9.px(BADGE_MARGIN)
            val half = size / 2f * t.badge
            dst.set(cx - half, cy - half, cx + half, cy + half)
            badgePaint.alpha = (t.badgeAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(closeBmp, null, dst, badgePaint)
        }
    }

    // ---- touch ----

    private var downX = 0f
    private var downY = 0f
    private var downIdx = -1
    private var badgeIdx = -1
    private var moved = false
    private val hold = Runnable {
        if (moved) return@Runnable
        setEditMode(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        moved = true // the release after a hold is not a tap
    }

    private val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var total = 1f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { total = 1f; removeCallbacks(hold); moved = true; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { total *= d.scaleFactor; return true }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            // SwitcherBaseStyle pinch-length 1.0: pinch in for the 3-column overview, out for detail.
            if (total < 0.85f && (mode === OVERVIEW || tiles.isEmpty())) {
                (context as? HomeActivity)?.enterCustomize(HomeActivity.SWITCHER); return
            }
            val target = when { total < 0.85f -> OVERVIEW; total > 1.15f -> DETAIL; else -> return }
            if (target !== mode && tiles.isNotEmpty()) { userMode = target; userModeCount = tiles.size; requestLayout() }
        }
    })

    private fun indexAt(x: Float, y: Float): Int {
        for ((i, t) in tiles.withIndex()) if (x >= t.left && x < t.right && y >= t.top && y < t.bottom) return i
        return -1
    }

    private fun badgeAt(x: Float, y: Float): Int {
        if (!editMode) return -1
        // Reactive margins: 0.8mm left/bottom, 1.6mm right/top around the 4.8mm button.
        for ((i, t) in tiles.withIndex()) {
            val size = N9.px(BADGE)
            val r = t.right - N9.px(BADGE_MARGIN) + N9.px(16f)
            val l = t.right - N9.px(BADGE_MARGIN) - size - N9.px(8f)
            val top = t.top + N9.px(BADGE_MARGIN) - N9.px(16f)
            val b = t.top + N9.px(BADGE_MARGIN) + size + N9.px(8f)
            if (x in l..r && y in top..b) return i
        }
        return -1
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        pinch.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = ev.pointerCount > 1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; moved = false
                badgeIdx = badgeAt(ev.x, ev.y)
                downIdx = if (badgeIdx >= 0) -1 else indexAt(ev.x, ev.y)
                // Holding a tile or the empty space: edit mode, with Home settings at the bottom.
                if (badgeIdx < 0 && !editMode) postDelayed(hold, LauncherGrid.HOLD_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true; removeCallbacks(hold)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > N9.px(40f) || abs(ev.y - downY) > N9.px(40f)) { moved = true; removeCallbacks(hold) }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                if (!moved) tap()
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(hold)
        }
        return true
    }

    private fun tap() {
        if (badgeIdx >= 0) { close(tiles[badgeIdx]); return }
        if (editMode) { setEditMode(false); return }
        val t = tiles.getOrNull(downIdx) ?: return
        panel.onOpen?.invoke(t.entry, t)
    }

    /** Height kept while close/reflow animations run (see onMeasure). */
    private var holdHeight = 0
    private val releaseHeight = Runnable { holdHeight = 0; requestLayout() }

    /**
     * Close every app: each tile shrinks away 20ms after the one before (SwipeSwitcherStyle
     * close-all-delay-increment), then edit mode ends with the empty view.
     */
    fun closeAll() {
        val all = tiles.filter { it.tag != "closing" }
        if (all.isEmpty()) { setEditMode(false); return }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        holdHeight = maxOf(holdHeight, measuredHeight)
        removeCallbacks(releaseHeight)
        postDelayed(releaseHeight, 100L + 20L * all.size + 250L + 100L)
        for ((i, t) in all.withIndex()) {
            t.tag = "closing"
            t.badge = 0f; t.badgeAlpha = 0f
            t.animate().cancel()
            val cx = t.width / 2f; val cy = t.height / 2f
            t.translationX += (cx - t.pivotX) * (t.scaleX - 1f)
            t.translationY += (cy - t.pivotY) * (t.scaleY - 1f)
            t.pivotX = cx; t.pivotY = cy
            t.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f).setStartDelay(100L + 20L * i).setDuration(250)
                .setInterpolator(Ease.inBack(2f)).setUpdateListener { invalidate() }
                .withEndAction { removeView(t) }.start()
            Apps.closeRecent(t.entry)
        }
        sync()
        setEditMode(false)
    }

    private fun close(t: SwitcherTile) {
        // Close (100 + 250ms) and the reflow after it (250ms delay, 20ms per tile, 250ms) must finish.
        holdHeight = maxOf(holdHeight, measuredHeight)
        removeCallbacks(releaseHeight)
        postDelayed(releaseHeight, 250L + 20L * tiles.size + 250L + 100L)
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        t.tag = "closing"
        t.badge = 0f; t.badgeAlpha = 0f
        // It may still be sliding into place after an earlier close: stop there, then shrink about its
        // centre (keeping where it is on screen and its current size).
        t.animate().cancel()
        val cx = t.width / 2f; val cy = t.height / 2f
        t.translationX += (cx - t.pivotX) * (t.scaleX - 1f)
        t.translationY += (cy - t.pivotY) * (t.scaleY - 1f)
        t.pivotX = cx; t.pivotY = cy
        // hide-delay 100, then 250ms inback (overshoot 2) down to scale 0.5.
        t.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f).setStartDelay(100).setDuration(250)
            .setInterpolator(Ease.inBack(2f)).setUpdateListener { invalidate() }
            .withEndAction { removeView(t) }.start()
        relayoutDelay = 250L
        Apps.closeRecent(t.entry)
        sync()
    }
}
