package com.amiigo.harmattan

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The application grid (SwipeLauncherView). Geometry, all from meegotouchhome.css (portrait):
 *  - SwipeLauncherButtonStyle: 11.2mm x 11.8mm, icon 8mm (LauncherButtonStyle), label FONT_XSMALL white,
 *    text-margin-top 0.8mm
 *  - LauncherPageFlowLayoutPolicy: vertical-spacing 3.0mm, horizontal-spacing 0.2mm
 *  - SwipeLauncherPannedWidget: padding-top 2.4mm, padding-left/right 1.3mm  => 4 columns fill 480px exactly
 *
 * Folders follow SwipeLauncherFolderButtonStyle / SwipeLauncherFolderContainerStyle / SwipeLauncherPageStyle,
 * and the N9 user guide: hold the view for edit mode, "New folder" in the toolbar, rename by tapping the name,
 * only empty folders can be removed.
 */
class LauncherPanel(ctx: Context) : FrameLayout(ctx) {
    val pannable = PannableView(ctx)
    val grid = LauncherGrid(ctx, this)
    // Edit mode's toolbar: "New folder".
    private val toolbar = EditToolbar(ctx, listOf(ctx.getString(R.string.qtn_home_launcher_new_folder) to { grid.addFolder() }))
    /** Tap on an app: (entry, button, icon rect in the button). */
    var onOpen: ((AppEntry, View, Rect) -> Unit)? = null

    init {
        pannable.content = grid
        addView(pannable, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        Apps.addListener { grid.sync() }
        grid.sync()
    }

    var bottomInset = 0
        set(v) { field = v; toolbar.bottomInset = v; updatePannableInset() }

    private fun updatePannableInset() {
        pannable.bottomInset = bottomInset + if (grid.editMode) N9.ipx(toolbar.barHeightN9) else 0
    }

    fun onEditModeChanged(on: Boolean) {
        toolbar.show(on)
        updatePannableInset()
    }

    fun exitEditMode() { grid.closeFolder(); grid.setEditMode(false) }
    val inEditMode get() = grid.editMode
    val folderOpen get() = grid.openFolder != null
    fun closeFolder() = grid.closeFolder()
}

/**
 * Edit-mode toolbar (MNavigationBarStyleInverted): slides in/out from the bottom, 250ms outexpo.
 * HEIGHT_NAVIGATIONBAR_PORTRAIT = 72px for one button; more buttons stack under it.
 */
@SuppressLint("ViewConstructor")
class EditToolbar(
    ctx: Context, private val buttons: List<Pair<String, () -> Unit>>,
    /** Buttons side by side (the switcher's Done / Close all) instead of stacked. */
    private val inRow: Boolean = false,
) : View(ctx) {
    companion object { const val PAD = 14f; const val PILL_H = 44f; const val GAP = 12f }
    private val rows get() = if (inRow) 1 else buttons.size
    val barHeightN9: Float get() = PAD * 2 + rows * PILL_H + (rows - 1) * GAP
    var bottomInset = 0
        set(v) { field = v; requestLayout() }
    private val bg = Paint().apply { color = 0xF2000000.toInt() }
    private val line = Paint().apply { color = 0xFF333333.toInt() }
    private val text = TextPaint(N9.textPaint(N9.bold, 22f, Color.WHITE)) // FONT_BUTTON: bold 22px
    private val pill = Pill(Pill.Kind.NORMAL)
    private val pillDown = Pill(Pill.Kind.PRESSED)
    private val rects = buttons.map { RectF() }
    private var down = -1
        set(v) { field = v; invalidate() }
    private var shownF = 0f
    val isUp: Boolean get() = shownF > 0.5f

    init { visibility = INVISIBLE }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), N9.ipx(barHeightN9) + bottomInset)

    fun show(on: Boolean) {
        visibility = VISIBLE
        val from = shownF
        ValueAnimator.ofFloat(from, if (on) 1f else 0f).apply {
            duration = 250; interpolator = Ease.outExpo
            addUpdateListener { shownF = it.animatedValue as Float; translationY = (1f - shownF) * height.coerceAtLeast(N9.ipx(barHeightN9)) }
            if (!on) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (shownF == 0f) visibility = INVISIBLE }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        canvas.drawRect(0f, 0f, width.toFloat(), max(1f, N9.px(1f)), line)
        val fm = text.fontMetrics
        val w = buttons.maxOf { max(N9.px(200f), text.measureText(it.first) + N9.px(48f)) }
        for ((i, b) in buttons.withIndex()) {
            val r = rects[i]
            if (inRow) {
                val n = buttons.size
                val bw = min(w, (width - N9.px(PAD) * 2 - N9.px(GAP) * (n - 1)) / n)
                val total = n * bw + (n - 1) * N9.px(GAP)
                val l = (width - total) / 2f + i * (bw + N9.px(GAP))
                r.set(l, N9.px(PAD), l + bw, N9.px(PAD + PILL_H))
            } else {
                val top = N9.px(PAD + i * (PILL_H + GAP))
                r.set(width / 2f - w / 2f, top, width / 2f + w / 2f, top + N9.px(PILL_H))
            }
            val d = if (down == i) pillDown else pill
            d.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()); d.draw(canvas)
            text.color = if (down == i) 0xFF797979.toInt() else Color.WHITE
            canvas.drawText(b.first, r.centerX() - text.measureText(b.first) / 2f, r.centerY() - (fm.ascent + fm.descent) / 2f, text)
        }
    }

    private fun hit(x: Float, y: Float) = rects.indexOfFirst { RectF(it).apply { inset(-N9.px(12f), -N9.px(5f)) }.contains(x, y) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (shownF < 0.5f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down = hit(e.x, e.y)
            MotionEvent.ACTION_MOVE -> if (down >= 0 && hit(e.x, e.y) != down) down = -1
            MotionEvent.ACTION_UP -> {
                val i = down; down = -1
                if (i >= 0) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); buttons[i].second() }
            }
            MotionEvent.ACTION_CANCEL -> down = -1
        }
        return true // the toolbar swallows touches behind it
    }
}

@SuppressLint("ViewConstructor")
class LauncherButton(ctx: Context, var item: LItem) : View(ctx) {
    private val textPaint = TextPaint(N9.textPaint(N9.regular, N9.FONT_XSMALL, N9.COLOR_INVERTED_FOREGROUND))
    private val iconPaint = Apps.iconPaint()
    private val iconRect = RectF()
    private val folderBmp = N9.bitmap(ctx, R.drawable.n9_app_folder)
    private val folderEmptyBmp = N9.bitmap(ctx, R.drawable.n9_app_folder_empty)

    // Close-button state (SwipeCloseButtonStyle), drawn by the grid on top of everything.
    var badge = 0f      // scale
    var badgeAlpha = 0f
    var targetOpacity = 1f
        private set
    private var opacityAnim: ValueAnimator? = null
    private var zoomAnim: ValueAnimator? = null
    private var badgeAnim: ValueAnimator? = null

    val entry: AppEntry? get() = (item as? LItem.App)?.e
    val folder: Folder? get() = (item as? LItem.Dir)?.f
    val key: String get() = item.key
    /** Close badge: removable apps, and folders only when empty. */
    val closable: Boolean get() = entry?.removable ?: (folder?.apps?.isEmpty() == true)
    val label: String get() = entry?.label ?: folder?.name.orEmpty()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(N9.ipx(LauncherGrid.BW), N9.ipx(LauncherGrid.BH))

    override fun onDraw(canvas: Canvas) {
        // The icon at its own size, bottom-aligned in its box so labels line up.
        val gm = LauncherGrid.m
        val box = N9.px(gm.iconBottom)
        val icon = N9.px(gm.icon)
        val x = (width - icon) / 2f
        iconRect.set(x, box - icon, x + icon, box)
        val f = folder
        if (f == null) canvas.drawBitmap(entry!!.icon, null, iconRect, iconPaint)
        else {
            canvas.drawBitmap(if (f.apps.isEmpty()) folderEmptyBmp else folderBmp, null, iconRect, iconPaint)
            // SwipeLauncherFolderButtonStyle: small-icon-size 2.6mm, small-icon-spacing 0.5mm -> 2x2 in the 8mm icon.
            val k = Apps.iconScale
            val s = N9.px(26f) * k; val sp = N9.px(5f) * k
            val block = 2 * s + sp
            val ox = x + (icon - block) / 2f; val oy = iconRect.top + (icon - block) / 2f
            for ((i, a) in f.apps.take(4).withIndex()) {
                val l = ox + (i % 2) * (s + sp); val t = oy + (i / 2) * (s + sp)
                canvas.drawBitmap(a.icon, null, RectF(l, t, l + s, t + s), iconPaint)
            }
        }
        val text = TextUtils.ellipsize(label, textPaint, width.toFloat(), TextUtils.TruncateAt.END).toString()
        val tw = textPaint.measureText(text)
        val fm = textPaint.fontMetrics
        // text-margin-top 0.8mm below the icon.
        canvas.drawText(text, (width - tw) / 2f, box + N9.px(8f) - fm.ascent, textPaint)
    }

    fun iconRect(): Rect {
        val box = N9.ipx(LauncherGrid.m.iconBottom)
        val icon = N9.ipx(LauncherGrid.m.icon)
        val x = (width - icon) / 2
        return Rect(x, box - icon, x + icon, box)
    }

    /** SwipeLauncherButtonStyle: opacity-duration 250, opacity-easing-curve outexpo (or as given). */
    fun animateOpacity(to: Float, curve: android.animation.TimeInterpolator = Ease.outExpo) {
        targetOpacity = to
        opacityAnim?.cancel()
        if (alpha == to) return
        opacityAnim = ValueAnimator.ofFloat(alpha, to).apply {
            duration = 250; interpolator = curve
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    /** zoom-duration 250, zoom-easing-curve outexpo (or as given). */
    fun animateZoom(to: Float, curve: android.animation.TimeInterpolator = Ease.outExpo, end: (() -> Unit)? = null) {
        zoomAnim?.cancel()
        zoomAnim = ValueAnimator.ofFloat(scaleX, to).apply {
            duration = 250; interpolator = curve
            addUpdateListener { val v = it.animatedValue as Float; scaleX = v; scaleY = v }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = end()
            })
            start()
        }
    }

    /** SwipeCloseButtonStyle: in 400ms (scale overshotbezier, opacity outexpo), out 250ms outexpo. */
    fun animateBadge(show: Boolean, onFrame: () -> Unit) {
        badgeAnim?.cancel()
        val from = badge
        val fromA = badgeAlpha
        badgeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (show) 400 else 250
            interpolator = Ease.linear
            addUpdateListener {
                val t = it.animatedFraction
                val target = if (show) 1f else 0f
                val sc = if (show) Ease.overshotBezier.getInterpolation(t) else Ease.outExpo.getInterpolation(t)
                badge = from + (target - from) * sc
                badgeAlpha = fromA + (target - fromA) * Ease.outExpo.getInterpolation(t)
                onFrame()
            }
            start()
        }
    }
}

@SuppressLint("ViewConstructor")
class LauncherGrid(ctx: Context, private val panel: LauncherPanel) : ViewGroup(ctx) {
    companion object {
        const val COLS = 4
        // SwipeLauncherButtonStyle 11.2mm x 11.8mm with 0.2mm / 3mm spacing at the N9's sizes.
        const val N9_BW = 112f
        const val N9_BH = 118f

        /**
         * The grid's measures for an interface scale [k] (spacing), icon size and text size. Each
         * element keeps its own size; cells grow to fit a larger icon or label, so nothing overlaps.
         */
        class Metrics(val bw: Float, val bh: Float, val hs: Float, val vs: Float, val padL: Float, val padT: Float,
                      val padB: Float, val icon: Float, val iconBottom: Float)

        fun metrics(k: Float, iconScale: Float, textScale: Float): Metrics {
            val icon = 80f * iconScale
            // Small icons stay bottom-aligned in the N9's 8mm box (labels line up); large ones push down.
            val iconBottom = maxOf(80f, icon)
            val label = N9.FONT_XSMALL * textScale * 1.35f
            val bw = maxOf(N9_BW * k, icon + 12f)
            val bh = maxOf(N9_BH * k, iconBottom + 8f + label + 4f)
            return Metrics(bw, bh, 2f * k, 30f * k, 13f * k, 24f * k, 24f * k, icon, iconBottom)
        }

        private var cached: Metrics? = null
        private var cacheKey = ""
        val m: Metrics get() {
            val key = "${Apps.iconScale}|${N9.textScale}"
            if (key != cacheKey || cached == null) { cached = metrics(1f, Apps.iconScale, N9.textScale); cacheKey = key }
            return cached!!
        }
        val BW: Float get() = m.bw
        val BH: Float get() = m.bh
        val HS: Float get() = m.hs
        val VS: Float get() = m.vs
        val PAD_L: Float get() = m.padL
        val PAD_T: Float get() = m.padT
        val PAD_B: Float get() = m.padB

        // close-button-position: 6.0mm -2.4mm; widget 6.4mm, icon 3.6mm
        const val BADGE_X = 60f
        const val BADGE_Y = -24f
        const val BADGE_SIZE = 64f
        const val BADGE_ICON = 36f

        const val HOLD_MS = 500L          // blanco MTapAndHoldRecognizerStyle timeout
        const val EDIT_HOLD_MS = 200L     // SwipeLauncherStyle edit-mode-drag-start-hold-timeout
        const val MOVE_THRESHOLD = 40f    // tap-and-hold movement-threshold: 4mm
        const val DRAG_PAN_ZONE = 80f     // SwipeLauncherPageStyle drag-pan-start-threshold
        const val DRAG_PAN_FACTOR = 0.2f  // drag-pan-velocity-factor
        const val DRAG_PAN_MAX = 12f      // drag-pan-maximum-velocity (per tick)

        // Folder container (SwipeLauncherPageStyle / SwipeLauncherFolderContainerStyle)
        const val F_OFFSET = 80f          // folder-content-vertical-offset-from-button-top: 8.0mm
        const val F_ARROW_W = 15f         // folder-arrow-head-size: 1.5mm x 1mm
        const val F_ARROW_H = 10f
        const val F_PAD = 6f              // folderContainerContentWidget padding: $PADDING_MEDIUM
        const val F_NAME_H = 48f
        const val F_MIN_PAGE = 118f       // LauncherPageStyle#folderPage minimum-size 11.8mm
        const val F_GRADIENT = 10f        // folder-gradient-size: 100% x 1mm
        const val F_BELOW = 24f
    }

    private val items = ArrayList<LItem>()
    private val buttons = HashMap<String, LauncherButton>()
    private val mainButtons = ArrayList<LauncherButton>()
    private val folderButtons = ArrayList<LauncherButton>()
    var editMode = false
        private set
    var openFolder: Folder? = null
        private set
    private var folderP = 0f
    private var folderAnim: ValueAnimator? = null
    private var following = false
    private val closeBmp = N9.bitmap(ctx, R.drawable.n9_close_thumbnail)
    private val gradientBmp = N9.bitmap(ctx, R.drawable.n9_gradient)
    private val badgePaint = Apps.iconPaint()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF505050.toInt(); style = Paint.Style.STROKE }
    private val namePaint = TextPaint(N9.textPaint(N9.regular, N9.FONT_DEFAULT, Color.WHITE))
    private val nameBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = (0.7f * 255).toInt() }
    private val nameEdit = EditText(ctx).apply {
        visibility = GONE
        background = null
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, N9.px(N9.FONT_DEFAULT) * N9.textScale)
        typeface = N9.regular
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(N9.ipx(12), 0, N9.ipx(12), 0)
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        addView(nameEdit)
        nameEdit.setOnEditorActionListener { _, a, ev ->
            if (a == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { finishRename(); true } else false
        }
        nameEdit.setOnFocusChangeListener { _, has -> if (!has) finishRename() }
    }

    // ---- model ----

    private fun buttonFor(i: LItem): LauncherButton {
        val b = buttons[i.key] ?: LauncherButton(context, i).also { buttons[i.key] = it; addView(it) }
        b.item = i
        b.invalidate()
        return b
    }

    fun sync() {
        items.clear(); items += Apps.items
        val openId = openFolder?.id
        openFolder = items.filterIsInstance<LItem.Dir>().firstOrNull { it.f.id == openId }?.f
        rebuildButtons()
        requestLayout()
    }

    private fun rebuildButtons() {
        val wanted = HashSet<String>()
        mainButtons.clear()
        for (i in items) { mainButtons += buttonFor(i); wanted += i.key }
        folderButtons.clear()
        openFolder?.let { f -> for (a in f.apps) { val li = LItem.App(a); folderButtons += buttonFor(li); wanted += li.key } }
        val dead = buttons.keys.filter { it !in wanted && buttons[it] !== dragged }
        for (k in dead) { removeView(buttons[k]); buttons.remove(k) }
        if (editMode) for (b in mainButtons + folderButtons) if (b.closable && b.badge == 0f) b.animateBadge(true) { invalidate() }
        updateOpacities()
    }

    private fun commit() {
        Apps.setItems(items)
        mainButtons.forEach { it.invalidate() }
    }

    fun addFolder() {
        val f = Apps.newFolder().also { it.name = context.getString(R.string.qtn_home_launcher_folder_default_name) }
        closeFolder()
        items += LItem.Dir(f)
        commit()
        rebuildButtons()
        requestLayout()
        val b = buttons["f:" + f.id] ?: return
        b.scaleX = 0f; b.scaleY = 0f
        b.animateZoom(1f, Ease.overshotBezier)
        post { panel.pannable.nudge(1e6f) }
    }

    // ---- geometry ----

    /** As many columns as the (scaled) screen fits — 4 at 100%, like the N9 — centred. */
    /** The grid's own width in N9 px (narrower when the Applications panel is drawn larger). */
    private var availN9 = N9.widthN9
    private val cols: Int get() = max(3, ((availN9 - 2 * PAD_L + HS) / (BW + HS)).toInt())
    private val padL: Float get() = (availN9 - (cols * BW + (cols - 1) * HS)) / 2f

    private fun slotX(i: Int) = N9.px(padL + (i % cols) * (BW + HS))
    private fun slotY(i: Int) = N9.px(PAD_T + (i / cols) * (BH + VS))

    private val openIdx: Int get() = openFolder?.let { f -> items.indexOfFirst { it is LItem.Dir && it.f === f } } ?: -1

    private fun folderRows() = max(1, ceil((openFolder?.apps?.size ?: 0) / cols.toFloat()).toInt())

    /** Full (unanimated) folder container box, in grid coordinates. */
    private fun boxRect(): RectF {
        val i = openIdx
        if (i < 0) return RectF()
        val top = slotY(i) + N9.px(F_OFFSET + F_ARROW_H)
        val rows = folderRows()
        val pageH = max(N9.px(F_MIN_PAGE), rows * N9.px(BH) + (rows - 1) * N9.px(VS))
        val bottom = top + N9.px(F_PAD + F_NAME_H) + pageH + N9.px(F_PAD + 16f)
        return RectF(0f, top, width.toFloat(), bottom)
    }

    private fun nameRect(): RectF {
        val b = boxRect()
        return RectF(N9.px(padL + 2f), b.top + N9.px(F_PAD), b.right - N9.px(padL + 2f), b.top + N9.px(F_PAD + F_NAME_H - 6f))
    }

    /** How far rows below the open folder move down. */
    private fun shift(): Float {
        val i = openIdx
        if (i < 0) return 0f
        val nextRowTop = slotY(i) + N9.px(BH + VS)
        return max(0f, boxRect().bottom + N9.px(F_BELOW) - nextRowTop)
    }

    private fun mainY(i: Int): Float {
        val oi = openIdx
        val extra = if (oi >= 0 && i / cols > oi / cols) shift() * folderP else 0f
        return slotY(i) + extra
    }

    private fun folderSlot(j: Int): Pair<Float, Float> {
        val b = boxRect()
        return slotX(j) to b.top + N9.px(F_PAD + F_NAME_H) + (j / cols) * N9.px(BH + VS)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 }?.let { availN9 = it / N9.s }
        for (b in buttons.values) b.measure(0, 0)
        val rows = ceil(items.size / cols.toFloat()).toInt().coerceAtLeast(1)
        var h = N9.px(PAD_T + rows * BH + (rows - 1) * VS + PAD_B)
        if (openIdx >= 0) {
            h += shift() * folderP
            h = max(h, boxRect().bottom * 1f + N9.px(F_BELOW))
        }
        nameEdit.measure(MeasureSpec.makeMeasureSpec(nameRect().width().toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(nameRect().height().toInt().coerceAtLeast(1), MeasureSpec.EXACTLY))
        // At least the visible area, so holding the empty space below the apps reaches the grid too.
        val viewport = panel.pannable.height - panel.pannable.bottomInset
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), maxOf(h.toInt(), viewport))
    }

    private var laidOut = false

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(animated = laidOut && !following)
        laidOut = true
        val n = nameRect()
        nameEdit.layout(n.left.toInt(), n.top.toInt(), n.right.toInt(), n.bottom.toInt())
    }

    /** SwipeLauncherLayoutAnimationStyle: 250ms outexpo, start-animation-delay 10ms. */
    private fun place(animated: Boolean) {
        val all = mainButtons.mapIndexed { i, btn -> Triple(btn, slotX(i), mainY(i)) } +
            folderButtons.mapIndexed { j, btn -> val (x, y) = folderSlot(j); Triple(btn, x, y) }
        for ((btn, fx, fy) in all) {
            val nx = fx.toInt(); val ny = fy.toInt()
            val oldX = btn.left + btn.translationX
            val oldY = btn.top + btn.translationY
            val wasPlaced = btn.isLaidOut && btn.tag != "new"
            btn.layout(nx, ny, nx + btn.measuredWidth, ny + btn.measuredHeight)
            btn.tag = null
            if (btn === dragged) { btn.translationX = oldX - nx; btn.translationY = oldY - ny; continue }
            if (animated && wasPlaced && (oldX != nx.toFloat() || oldY != ny.toFloat())) {
                btn.translationX = oldX - nx; btn.translationY = oldY - ny
                btn.animate().translationX(0f).translationY(0f).setDuration(250).setStartDelay(10)
                    .setInterpolator(Ease.outExpo).setUpdateListener { invalidate() }.start()
            } else if (!animated && !following) {
                btn.translationX = 0f; btn.translationY = 0f
            }
        }
        // Buttons that belong to neither list (a closing folder's apps) stay where they were.
    }

    // ---- states ----

    fun setEditMode(on: Boolean) {
        if (editMode == on) return
        editMode = on
        for (b in mainButtons + folderButtons) if (b.closable) b.animateBadge(on) { invalidate() }
        updateOpacities()
        panel.onEditModeChanged(on)
        if (!on) finishRename()
        invalidate()
    }

    /** SwipeLauncherButtonStyle states: edit-mode-enabled 0.5, background 0.5 / background-edit-mode 0.25, open-folder 1 / 0.5. */
    private fun updateOpacities() {
        val f = openFolder
        for (b in mainButtons) {
            if (b === dragged) continue
            val to = when {
                f != null && b.folder === f -> if (editMode) 0.5f else 1f
                f != null -> if (editMode) 0.25f else 0.5f
                editMode -> 0.5f
                else -> 1f
            }
            b.animateOpacity(to)
        }
        for (b in folderButtons) if (b !== dragged) b.animateOpacity(if (editMode) 0.5f else 1f)
    }

    fun toggleFolder(f: Folder) { if (openFolder === f) closeFolder() else openFolderAnimated(f) }

    private fun openFolderAnimated(f: Folder) {
        if (openFolder != null) closeFolder(animated = false)
        openFolder = f
        rebuildButtons()
        for (b in folderButtons) { b.alpha = 0f; b.tag = "new" }
        animateFolder(1f) {
            // Keep the opened folder on screen (folder-content-minimum-bottom-margin 14.7mm).
            val bottom = boxRect().bottom - panel.pannable.pos
            val visible = panel.pannable.height - panel.pannable.bottomInset - N9.px(if (editMode) 75f else 147f)
            if (bottom > visible) smoothNudge(bottom - visible)
        }
    }

    fun closeFolder(animated: Boolean = true) {
        val f = openFolder ?: return
        finishRename()
        if (!animated) { folderP = 0f; openFolder = null; rebuildButtons(); requestLayout(); return }
        animateFolder(0f) { if (openFolder === f) { openFolder = null; rebuildButtons(); requestLayout() } }
    }

    /**
     * SwipeLauncherFolderContainerStyle: size-change-animation 250ms outexpo; contents fade in with
     * outexpo (open-animation-start-opacity 0) and out with outsine (close-animation-target-opacity 0).
     */
    private fun animateFolder(to: Float, end: () -> Unit) {
        folderAnim?.cancel()
        val from = folderP
        val opening = to > from
        if (opening) updateOpacities() else {
            // Main buttons return to normal opacity as the folder closes.
            for (b in mainButtons) b.animateOpacity(if (editMode) 0.5f else 1f)
        }
        folderAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            addUpdateListener {
                val t = it.animatedFraction
                folderP = from + (to - from) * Ease.outExpo.getInterpolation(t)
                val o = if (opening) Ease.outExpo.getInterpolation(t) else 1f - Ease.outSine.getInterpolation(t)
                for (b in folderButtons) if (b !== dragged) b.alpha = o * (if (editMode) 0.5f else 1f)
                following = true
                requestLayout()
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { following = false; end() }
            })
            start()
        }
    }

    private fun smoothNudge(dy: Float) {
        var done = 0f
        ValueAnimator.ofFloat(0f, dy).apply {
            duration = 250; interpolator = Ease.outExpo
            addUpdateListener { val v = it.animatedValue as Float; panel.pannable.nudge(v - done); done = v }
            start()
        }
    }

    // ---- rename ----

    private fun startRename() {
        val f = openFolder ?: return
        nameEdit.setText(f.name)
        nameEdit.visibility = VISIBLE
        nameEdit.requestFocus()
        nameEdit.setSelection(nameEdit.text.length)
        context.getSystemService(InputMethodManager::class.java).showSoftInput(nameEdit, 0)
        invalidate()
    }

    private fun finishRename() {
        if (nameEdit.visibility != VISIBLE) return
        val f = openFolder
        val name = nameEdit.text.toString().trim()
        if (f != null && name.isNotEmpty()) { f.name = name; commit() }
        nameEdit.visibility = GONE
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(nameEdit.windowToken, 0)
        nameEdit.clearFocus()
        invalidate()
    }

    // ---- drawing ----

    override fun dispatchDraw(canvas: Canvas) {
        val time = drawingTime
        for (b in mainButtons) if (b !== dragged) drawChild(canvas, b, time)
        // Apps of a folder that is closing (no longer listed) still fade out inside the box.
        if (folderP > 0.001f && openIdx >= 0) {
            val box = boxRect()
            val h = (box.bottom - box.top) * folderP
            val r = RectF(box.left, box.top, box.right, box.top + h)
            drawBox(canvas, r)
            canvas.save()
            canvas.clipRect(r)
            for (b in folderButtons) if (b !== dragged) drawChild(canvas, b, time)
            if (nameEdit.visibility == VISIBLE) drawChild(canvas, nameEdit, time) else drawName(canvas)
            canvas.restore()
        }
        dragged?.let { drawChild(canvas, it, time) }
        val dst = RectF()
        for (b in mainButtons + folderButtons) {
            if (b.badgeAlpha <= 0.001f || b.badge <= 0.001f) continue
            if (b in folderButtons && folderP < 0.99f) continue
            val cx = b.left + b.translationX + N9.px(BADGE_X + BADGE_SIZE / 2)
            val cy = b.top + b.translationY + N9.px(BADGE_Y + BADGE_SIZE / 2)
            val half = N9.px(BADGE_ICON) / 2f * b.badge
            dst.set(cx - half, cy - half, cx + half, cy + half)
            badgePaint.alpha = (b.badgeAlpha * 255 * (if (b in mainButtons && openFolder != null && b.folder !== openFolder) 0.5f else 1f)).toInt().coerceIn(0, 255)
            canvas.drawBitmap(closeBmp, null, dst, badgePaint)
        }
    }

    /** Folder container: black, 0.1mm border, 1mm gradients, 1.5mm x 1mm arrow head toward the folder icon. */
    private fun drawBox(canvas: Canvas, r: RectF) {
        val i = openIdx
        val ax = slotX(i) + N9.px(BW / 2f)
        val aw = N9.px(F_ARROW_W) / 2f; val ah = N9.px(F_ARROW_H) * min(1f, folderP * 2f)
        val path = Path().apply {
            moveTo(r.left, r.top); lineTo(ax - aw, r.top); lineTo(ax, r.top - ah); lineTo(ax + aw, r.top)
            lineTo(r.right, r.top); lineTo(r.right, r.bottom); lineTo(r.left, r.bottom); close()
        }
        canvas.drawPath(path, boxPaint)
        val g = N9.px(F_GRADIENT)
        canvas.drawBitmap(gradientBmp, null, RectF(r.left, r.top, r.right, min(r.bottom, r.top + g)), badgePaint.apply { alpha = 255 })
        canvas.save(); canvas.scale(1f, -1f, 0f, r.bottom - g / 2f)
        canvas.drawBitmap(gradientBmp, null, RectF(r.left, r.bottom - g, r.right, r.bottom), badgePaint)
        canvas.restore()
        borderPaint.strokeWidth = max(1f, N9.px(1f))
        val top = Path().apply { moveTo(r.left, r.top); lineTo(ax - aw, r.top); lineTo(ax, r.top - ah); lineTo(ax + aw, r.top); lineTo(r.right, r.top) }
        canvas.drawPath(top, borderPaint)
        canvas.drawLine(r.left, r.bottom, r.right, r.bottom, borderPaint)
    }

    /** MTextEditStyle#folderNameWidget (white, no frame) / #folderNameWidgetEditMode (white frame 0.7, black text). */
    private fun drawName(canvas: Canvas) {
        val f = openFolder ?: return
        val n = nameRect()
        val fm = namePaint.fontMetrics
        val baseline = n.centerY() - (fm.ascent + fm.descent) / 2f
        if (editMode) {
            canvas.drawRoundRect(n, N9.px(8f), N9.px(8f), nameBgPaint)
            namePaint.color = Color.BLACK
            canvas.drawText(f.name, n.left + N9.px(12f), baseline, namePaint)
        } else {
            namePaint.color = Color.WHITE
            canvas.drawText(f.name, n.left, baseline, namePaint)
        }
    }

    // ---- touch ----

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downBtn: LauncherButton? = null
    private var badgeBtn: LauncherButton? = null
    private var downOnName = false
    private var downInBox = false
    private var moved = false
    private var dragged: LauncherButton? = null
    private var dragFromFolder = false
    private var dragOriginX = 0f
    private var dragOriginY = 0f
    private var hoverFolder: LauncherButton? = null

    private val holdRunnable = Runnable {
        if (moved) return@Runnable
        moved = true // the finger lifting after a hold isn't a tap (which would leave edit mode again)
        val b = downBtn
        if (!editMode) setEditMode(true)
        if (b != null) startDrag(b)
        else performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun buttonAt(x: Float, y: Float): LauncherButton? {
        val box = if (openFolder != null && folderP > 0.5f) boxRect() else null
        if (box != null && box.contains(x, y)) {
            for ((j, b) in folderButtons.withIndex()) {
                val (l, t) = folderSlot(j)
                if (x >= l && x < l + N9.px(BW) && y >= t && y < t + N9.px(BH)) return b
            }
            return null
        }
        for ((i, b) in mainButtons.withIndex()) {
            val l = slotX(i); val t = mainY(i)
            if (x >= l && x < l + N9.px(BW) && y >= t && y < t + N9.px(BH)) {
                // The open box covers the labels of its own row.
                if (box != null && y >= box.top - N9.px(F_ARROW_H)) return null
                return b
            }
        }
        return null
    }

    private fun badgeAt(x: Float, y: Float): LauncherButton? {
        if (!editMode) return null
        val list = if (openFolder != null && folderP > 0.99f) folderButtons + mainButtons.filter { it.folder === openFolder } else mainButtons
        for (b in list) {
            if (!b.closable || b.badge < 0.5f) continue
            val l = b.left + b.translationX + N9.px(BADGE_X)
            val t = b.top + b.translationY + N9.px(BADGE_Y)
            if (x >= l && x < l + N9.px(BADGE_SIZE) && y >= t && y < t + N9.px(BADGE_SIZE)) return b
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                moved = false
                badgeBtn = badgeAt(ev.x, ev.y)
                downOnName = badgeBtn == null && openFolder != null && folderP > 0.99f && nameRect().contains(ev.x, ev.y)
                downInBox = openFolder != null && boxRect().contains(ev.x, ev.y)
                downBtn = if (badgeBtn != null || downOnName) null else buttonAt(ev.x, ev.y)
                if (badgeBtn == null && !downOnName) postDelayed(holdRunnable, if (editMode && downBtn != null) EDIT_HOLD_MS else HOLD_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = ev.x; lastY = ev.y
                if (dragged != null) { drag(); return true }
                if (abs(ev.x - downX) > N9.px(MOVE_THRESHOLD) || abs(ev.y - downY) > N9.px(MOVE_THRESHOLD)) {
                    moved = true; removeCallbacks(holdRunnable)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                if (dragged != null) { drop(); return true }
                if (!moved) tap()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                if (dragged != null) drop()
            }
        }
        return true
    }

    private fun tap() {
        badgeBtn?.let { b ->
            b.entry?.let { Apps.uninstall(it) }
            b.folder?.let { f -> if (f.apps.isEmpty()) removeFolder(b, f) }
            return
        }
        if (downOnName) { startRename(); return }
        val b = downBtn
        if (b == null) {
            when {
                downInBox -> {}                         // empty part of the folder page
                openFolder != null -> closeFolder()
                editMode -> setEditMode(false)
            }
            return
        }
        b.folder?.let { toggleFolder(it); return }
        if (editMode) return
        val e = b.entry ?: return
        panel.onOpen?.invoke(e, b, b.iconRect())
    }

    private fun removeFolder(b: LauncherButton, f: Folder) {
        // close-animation-zoom 0.5, close-animation-duration 250, close-animation-easing-curve inback
        b.animateOpacity(0f, Ease.inBack)
        b.animateZoom(0.5f, Ease.inBack) {
            items.removeAll { it is LItem.Dir && it.f === f }
            commit(); rebuildButtons(); requestLayout()
        }
    }

    private fun startDrag(b: LauncherButton) {
        dragFromFolder = b in folderButtons
        if (!dragFromFolder && openFolder != null) closeFolder(animated = false)
        dragged = b
        parent?.requestDisallowInterceptTouchEvent(true)
        // Where the finger holds the icon, measured from the icon's on-screen position. The icon then
        // follows the finger in grid coordinates, whatever slot the reflow gives it underneath.
        dragOriginX = downX - (b.left + b.translationX)
        dragOriginY = downY - (b.top + b.translationY)
        b.animate().cancel()
        b.animateOpacity(1f)  // :edit-mode-being-dragged opacity 1.0, zoom 1.15
        b.animateZoom(1.15f)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        autoPanNs = 0L
        postOnAnimation(autoPan)
        invalidate()
    }

    private fun drag() {
        val b = dragged ?: return
        b.translationX = lastX - dragOriginX - b.left
        b.translationY = lastY - dragOriginY - b.top
        invalidate()
        val cx = b.left + b.translationX + b.width / 2f
        val cy = b.top + b.translationY + N9.px(40f) // icon centre
        if (dragFromFolder) {
            val f = openFolder ?: return
            val box = boxRect()
            if (lastY < box.top - N9.px(30f) || lastY > box.bottom + N9.px(30f)) { pullOutOfFolder(b, f); return }
            val col = ((cx - N9.px(padL)) / N9.px(BW + HS)).toInt().coerceIn(0, cols - 1)
            val row = ((cy - (box.top + N9.px(F_PAD + F_NAME_H))) / N9.px(BH + VS)).toInt().coerceAtLeast(0)
            val target = min(row * cols + col, f.apps.size - 1)
            val cur = folderButtons.indexOf(b)
            if (target >= 0 && target != cur) {
                folderButtons.removeAt(cur); folderButtons.add(target, b)
                val e = f.apps.removeAt(cur); f.apps.add(target, e)
                place(animated = true)
            }
            return
        }
        // Hovering an app over a folder's icon (where it is on screen now):
        // SwipeLauncherButtonStyle:active-folder (opacity 1.0).
        val over = if (b.entry != null) mainButtons.firstOrNull { other ->
            if (other === b || other.folder == null) return@firstOrNull false
            val l = other.left + other.translationX; val t = other.top + other.translationY
            RectF(l + N9.px(16f), t, l + N9.px(96f), t + N9.px(80f)).apply { inset(-N9.px(8f), -N9.px(8f)) }.contains(cx, cy)
        } else null
        if (over !== hoverFolder) {
            hoverFolder?.animateOpacity(0.5f)
            hoverFolder = over
            over?.animateOpacity(1f)
            if (over != null) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        if (over != null) { removeCallbacks(reorder); pendingTarget = -1; return }
        val col = ((cx - N9.px(padL)) / N9.px(BW + HS)).toInt().coerceIn(0, cols - 1)
        val row = ((cy - N9.px(PAD_T)) / N9.px(BH + VS)).toInt().coerceAtLeast(0)
        val target = min(row * cols + col, mainButtons.size - 1)
        val cur = mainButtons.indexOf(b)
        // An app level with a folder's icon is on its way into it: don't push the folder away.
        val tb = mainButtons.getOrNull(target)
        val towardFolder = b.entry != null && tb?.folder != null && cy < tb.top + tb.translationY + N9.px(80f + 8f)
        // Reorder once the icon rests on a slot, so passing over icons doesn't shuffle them.
        if (target != cur && target >= 0 && !towardFolder) {
            if (target != pendingTarget) { pendingTarget = target; removeCallbacks(reorder); postDelayed(reorder, EDIT_HOLD_MS) }
        } else { pendingTarget = -1; removeCallbacks(reorder) }
    }

    private var pendingTarget = -1
    private val reorder = Runnable {
        val b = dragged ?: return@Runnable
        val target = pendingTarget
        pendingTarget = -1
        val cur = mainButtons.indexOf(b)
        if (target < 0 || cur < 0 || target == cur || dragFromFolder) return@Runnable
        mainButtons.removeAt(cur); mainButtons.add(target, b)
        val it = items.removeAt(cur); items.add(target, it)
        place(animated = true)
    }

    private fun pullOutOfFolder(b: LauncherButton, f: Folder) {
        val e = b.entry ?: return
        val fi = openIdx
        f.apps.remove(e)
        folderButtons.remove(b)
        val li = LItem.App(e)
        b.item = li
        buttons.remove("a:" + e.key); buttons[li.key] = b
        items.add(fi + 1, li)
        mainButtons.add(fi + 1, b)
        dragFromFolder = false
        closeFolder(animated = true)
        commit()
    }

    private var autoPanNs = 0L

    /** Auto-scroll while dragging near the top/bottom edge. Speeds are per 60 Hz frame, scaled to the real frame time. */
    private val autoPan = object : Runnable {
        override fun run() {
            dragged ?: return
            val now = System.nanoTime()
            val frames = if (autoPanNs == 0L) 1f else ((now - autoPanNs) / 16_666_667f).coerceIn(0.25f, 4f)
            autoPanNs = now
            val visTop = panel.pannable.pos
            val visBottom = visTop + panel.pannable.height - panel.pannable.bottomInset
            val zone = N9.px(DRAG_PAN_ZONE)
            val y = lastY
            val v = when {
                y < visTop + zone -> -min(N9.px(DRAG_PAN_MAX), (visTop + zone - y) * DRAG_PAN_FACTOR)
                y > visBottom - zone -> min(N9.px(DRAG_PAN_MAX), (y - (visBottom - zone)) * DRAG_PAN_FACTOR)
                else -> 0f
            }
            if (v != 0f) {
                val applied = panel.pannable.nudge(v * frames)
                lastY += applied
                drag()
            }
            postOnAnimation(this)
        }
    }

    private fun drop() {
        val b = dragged ?: return
        removeCallbacks(autoPan)
        removeCallbacks(reorder); pendingTarget = -1
        val target = hoverFolder
        hoverFolder = null
        val e = b.entry
        if (target != null && e != null && target.folder != null) {
            // :edit-mode-dropped-to-folder — opacity 0 and zoom 0.5, both inback.
            val f = target.folder!!
            b.animateOpacity(0f, Ease.inBack)
            b.animateZoom(0.5f, Ease.inBack) {
                dragged = null
                b.translationZ = 0f
                items.removeAll { it is LItem.App && it.e.key == e.key }
                f.apps += e
                commit(); rebuildButtons(); requestLayout()
                target.invalidate()
            }
            target.animateOpacity(if (editMode) 0.5f else 1f)
            return
        }
        dragged = null
        b.translationZ = 0f
        b.animateZoom(1f)
        updateOpacities()
        b.animateOpacity(if (editMode) 0.5f else 1f)
        b.animate().translationX(0f).translationY(0f).setDuration(250).setInterpolator(Ease.outExpo)
            .setUpdateListener { invalidate() }.start()
        commit()
        invalidate()
    }
}
