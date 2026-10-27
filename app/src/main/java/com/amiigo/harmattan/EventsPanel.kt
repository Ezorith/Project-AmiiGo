package com.amiigo.harmattan

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The events view (SwipeEventsView): date header, notification banners, and the feed.
 * Values from meegotouchhome.css and mbannerstyle.css; see each row class.
 */
/** The Now Playing row with playback controls; off until it's reworked. */
const val SHOW_NOW_PLAYING = false

class EventsPanel(ctx: Context) : FrameLayout(ctx) {
    companion object { const val BACK_TO_TOP_AFTER = 5 }
    /** How far the view is scrolled (its own px), for the wallpaper's dark top. */
    var onScroll: ((Float) -> Unit)? = null

    val pannable = PannableView(ctx)
    private val column = EventsColumn(ctx)

    private val toolbar = EditToolbar(ctx, listOf(
        ctx.getString(R.string.home_editor) to { hideToolbar(); (ctx as? HomeActivity)?.enterCustomize() ?: openHomeSettings(ctx) },
        ctx.getString(R.string.feeds) to { hideToolbar(); openFeeds() },
    ), inRow = true)

    /** The feed's settings, as their own sheet. */
    private fun openFeeds() { (context as? HomeActivity)?.openFeedSheet() }
    private val backToTop = BackToTopButton(ctx) { pannable.scrollToTop(true) }

    /** Today's own copy of the wallpaper, used while Home settings zooms out (it scales with Today). */
    val wallpaperLayer = WallpaperView(ctx).apply { active = false; topH = N9.px(110f) }

    init {
        addView(wallpaperLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pannable.content = column
        addView(pannable, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(backToTop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        pannable.onScrollChanged = { pos -> updateBackToTop(); onScroll?.invoke(pos) }
        EventStore.addListener { rebuild() }
        Weather.addListener { column.rowFor("date")?.invalidate() }
        MediaWatcher.addListener {
            val hadRow = column.rowFor("media") != null
            if (hadRow == (MediaWatcher.title != null)) column.rowFor("media")?.invalidate() else rebuild()
        }
        rebuild()
    }

    var bottomInset: Int
        get() = pannable.bottomInset
        set(v) {
            pannable.bottomInset = v
            toolbar.bottomInset = v
            backToTop.bottomInset = v
        }

    /** "Back to top" shows once [BACK_TO_TOP_AFTER] feed posts have scrolled up out of view. */
    private fun updateBackToTop() {
        val post = column.feedRow(BACK_TO_TOP_AFTER - 1)
        backToTop.show(post != null && pannable.pos > post.top + post.height && !toolbar.isUp)
    }

    fun hideToolbar() { if (toolbar.isUp) setEditing(false) }

    // Hold anywhere: the toolbar with Home settings (the touch then no longer opens what's under it).
    private var downX = 0f
    private var downY = 0f
    private var held = false
    private var downPos = 0f
    private val hold = Runnable {
        // A press that stopped a fling, or a slow scroll under the finger, is scrolling, not holding.
        if (kotlin.math.abs(pannable.pos - downPos) > N9.px(4f) || pannable.moving) return@Runnable
        held = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        superDispatch(cancel); cancel.recycle()
        backToTop.show(false)
        setEditing(true)
    }

    /** Held: the toolbar, Today dimmed as Applications and Recents are, and ✕ on dismissable notifications. */
    private fun setEditing(on: Boolean) {
        toolbar.show(on)
        column.setEditing(on)
        if (!on) updateBackToTop()
        // edit-mode-brightness 0.5 over the whole view, 250ms outexpo; the ✕ grow in with overshotbezier.
        editAnim?.cancel()
        val d0 = editDim; val b0 = editBadge
        val toDim = if (on) 0.5f else 0f; val toBadge = if (on) 1f else 0f
        editAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            addUpdateListener {
                val t = it.animatedFraction
                editDim = d0 + (toDim - d0) * Ease.outExpo.getInterpolation(t)
                editBadge = b0 + (toBadge - b0) * (if (on) Ease.overshotBezier.getInterpolation(t) else Ease.outExpo.getInterpolation(t))
                invalidate()
            }
            start()
        }
    }

    private var editDim = 0f
    private var editBadge = 0f
    private var editAnim: android.animation.ValueAnimator? = null
    private val dimPaint = android.graphics.Paint().apply { color = 0xFF000000.toInt() }
    private val badgePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    override fun drawChild(canvas: Canvas, child: android.view.View, drawingTime: Long): Boolean {
        val r = super.drawChild(canvas, child, drawingTime)
        if (child === pannable && editDim > 0.001f) {
            dimPaint.alpha = (editDim * 255).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            if (editBadge > 0.001f) {
                canvas.save()
                canvas.clipRect(pannable.left.toFloat(), pannable.top.toFloat(), pannable.right.toFloat(), pannable.bottom.toFloat())
                val bmp = Row.closeBmp(context)
                for (row in column.rowsList()) {
                    if (!row.dismissable) continue
                    val b = row.badgeRect()
                    val ox = pannable.left + column.left + column.translationX + row.left + row.translationX
                    val oy = pannable.top + column.top + column.translationY + row.top + row.translationY
                    val half = b.width() / 2f * editBadge
                    badgePaint.alpha = (row.alpha * 255).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(bmp, null, RectF(ox + b.centerX() - half, oy + b.centerY() - half, ox + b.centerX() + half, oy + b.centerY() + half), badgePaint)
                }
                canvas.restore()
                // Keep following the rows while held (scrolling, a dismissed one sliding out).
                postInvalidateOnAnimation()
            }
        }
        return r
    }

    private var badgeDown: Row? = null
    private var editTap = false

    /** The row whose ✕ is under ([x], [y]) in this view's coordinates. */
    private fun badgeAt(x: Float, y: Float): Row? {
        val cx = x - pannable.left - column.left
        val cy = y - pannable.top - column.top - column.translationY
        return column.rowsList().firstOrNull { r ->
            r.dismissable && r.badgeRect().apply { offset(r.left + r.translationX, r.top + r.translationY); inset(-N9.px(10f), -N9.px(10f)) }.contains(cx, cy)
        }
    }

    private fun superDispatch(e: MotionEvent) = super.dispatchTouchEvent(e)

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = false; downX = e.x; downY = e.y; downPos = pannable.pos
                val wasMoving = pannable.moving
                val onToolbar = toolbar.isUp && e.y >= toolbar.top + toolbar.translationY
                // Back to top and the dark band behind it never bring up the toolbar.
                val onButton = backToTop.inStrip(e.y)
                editTap = false
                if (toolbar.isUp && !onToolbar) {
                    // Held Today: ✕ dismisses a notification; any other tap puts it back (scrolling still works).
                    badgeDown = badgeAt(e.x, e.y)
                    if (badgeDown != null) return true
                    editTap = true
                } else if (!onToolbar && !onButton && !wasMoving) postDelayed(hold, LauncherGrid.HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (badgeDown != null) return true
                if (kotlin.math.abs(e.x - downX) > N9.px(LauncherGrid.MOVE_THRESHOLD) ||
                    kotlin.math.abs(e.y - downY) > N9.px(LauncherGrid.MOVE_THRESHOLD)) { removeCallbacks(hold); editTap = false }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                badgeDown?.let { r ->
                    badgeDown = null
                    if (e.actionMasked == MotionEvent.ACTION_UP && badgeAt(e.x, e.y) === r) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        (r as? BannerRow)?.item?.let { item -> column.clearAll(setOf("n:" + item.key)) { EventStore.dismiss(item) } }
                    }
                    return true
                }
                if (editTap && e.actionMasked == MotionEvent.ACTION_UP) { editTap = false; super.dispatchTouchEvent(e); hideToolbar(); return true }
            }
        }
        if (held) return true
        return super.dispatchTouchEvent(e)
    }

    fun rebuild() {
        val rows = ArrayList<Pair<String, () -> Row>>()
        rows += "date" to { DateRow(context) }
        // The Now Playing row (MediaRow) is set aside for now; players show as notifications.
        if (SHOW_NOW_PLAYING && MediaWatcher.title != null) {
            rows += "mdiv" to { DividerRow(context) }
            rows += "media" to { MediaRow(context) }
        }
        val notes = EventStore.notifications
        val feed = EventStore.feed
        if (notes.isNotEmpty()) {
            rows += "div1" to { DividerRow(context) }
            // The heading and Clear only when there's something to clear; ongoing ones (USB, charging…) list without it.
            if (notes.any { it.clearable }) rows += "nh" to {
                SectionRow(context, context.getString(R.string.qtn_noti_title), context.getString(R.string.qtn_noti_clear)) {
                    // Only what can be cleared goes: ongoing ones (USB, charging…) stay, and stay visible.
                    val clearable = EventStore.notifications.filter { it.clearable }.map { "n:" + it.key }.toSet()
                    column.clearAll(clearable) { EventStore.clearNotifications() }
                }
            }
            for (n in notes) rows += ("n:" + n.key) to { BannerRow(context, n) }
        }
        // The Feed heading (and Refresh) only once there's a feed: sources, or posts from social apps.
        val hasFeed = feed.isNotEmpty() || EventStore.rssSources.isNotEmpty()
        if (hasFeed) rows += "div2" to { DividerRow(context) }
        if (hasFeed) rows += "fh" to {
            SectionRow(context, context.getString(R.string.qtn_home_events_feed_title), context.getString(R.string.qtn_home_events_feed_refresh)) {
                EventStore.refresh()
            }
        }
        for ((i, f) in feed.withIndex()) {
            if (i > 0) rows += ("fs:" + f.key) to { SeparatorRow(context) }
            rows += ("f:" + f.key) to { FeedRow(context, f) }
        }
        column.setRows(rows, notes, feed)
        (column.rowFor("fh") as? SectionRow)?.buttonEnabled = !EventStore.refreshing
        // Nothing left that Clear can remove (only ongoing ones): Clear is greyed out.
        (column.rowFor("nh") as? SectionRow)?.buttonEnabled = notes.any { it.clearable }
    }

    fun onShown() {
        (column.rowFor("date") as? DateRow)?.invalidate()
        Weather.refresh()
        EventStore.markSeen()
        EventStore.maybeAutoUpdate()
    }
}

// ---------------------------------------------------------------------------------------------

/** Vertical stack with MBasicLayoutAnimation-style transitions. */
@SuppressLint("ViewConstructor")
class EventsColumn(ctx: Context) : ViewGroup(ctx) {
    companion object {
        const val PAD_L = 14f   // content inset; items add their own 0.2mm left margin
        const val PAD_T = 24f   // MWidgetStyle#SwipeEventsWidget margin-top: 2.4mm
        const val PAD_B = 24f
    }

    private val rows = LinkedHashMap<String, Row>()
    private var laidOut = false

    fun rowFor(key: String) = rows[key]
    fun rowsList(): List<Row> = rows.values.filter { it.tag != "gone" }
    /** The [i]th feed post's row (0 = the newest). */
    fun feedRow(i: Int): Row? = rows.entries.filter { it.key.startsWith("f:") }.getOrNull(i)?.value

    /** Today held: rows dim and dismissable notifications show their ✕. */
    var editing = false
        private set

    fun setEditing(on: Boolean) {
        if (editing == on) return
        editing = on
        for (r in rows.values) r.setEdit(on, animated = true)
    }

    fun setRows(spec: List<Pair<String, () -> Row>>, notes: List<EventItem>, feed: List<EventItem>) {
        val byKey = notes.associateBy { "n:" + it.key } + feed.associateBy { "f:" + it.key }
        val next = LinkedHashMap<String, Row>()
        for ((key, make) in spec) {
            val existing = rows[key]
            // Keep rows that already exist and just give them fresh data: re-creating them would fade
            // every banner in again each time notifications re-sync (the "flash" on opening this view).
            val row = if (existing != null && existing.tag != "gone") {
                if (existing is ItemRow) byKey[key]?.let { if (existing.item !== it) existing.item = it }
                // A row that stays is always fully shown (never left faded by a Clear).
                existing.alpha = 1f; existing.translationX = 0f
                existing
            } else {
                existing?.let { removeView(it) }
                make().also { r ->
                    r.setEdit(editing, animated = false)
                    addView(r)
                    if (laidOut) { r.alpha = 0f; r.tag = "new" }
                }
            }
            next[key] = row
        }
        for ((k, r) in rows) if (k !in next && r.parent === this) fadeOutAndRemove(r)
        rows.clear(); rows.putAll(next)
        requestLayout()
    }

    private fun fadeOutAndRemove(r: Row) {
        r.tag = "gone"
        r.animate().alpha(0f).setDuration(150).setInterpolator(Ease.outExpo).withEndAction { removeView(r) }.start()
    }

    /** NotificationAreaStyle clear-all-animation-duration: 150. */
    fun clearAll(keys: Set<String>, then: () -> Unit) {
        val banners = rows.filterKeys { it in keys }.values
        if (banners.isEmpty()) { then(); return }
        for (b in banners) b.animate().alpha(0f).translationX(N9.px(40f)).setDuration(150).setInterpolator(Ease.outExpo).start()
        postDelayed(then, 150)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val inner = w - N9.ipx(PAD_L)
        var h = N9.px(PAD_T)
        for (r in rows.values) {
            r.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            h += r.measuredHeight
        }
        h += N9.px(PAD_B)
        setMeasuredDimension(w, h.toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = N9.px(PAD_T).toInt()
        val x = N9.ipx(PAD_L)
        for (row in rows.values) {
            val oldTop = row.top + row.translationY
            val placed = row.isLaidOut && row.tag != "new"
            row.layout(x, y, x + row.measuredWidth, y + row.measuredHeight)
            if (row.tag == "new") {
                // SwipeEventFeedArea / NotificationArea MBasicLayoutAnimationStyle:
                // initial-showing-opacity 0, 400ms, opacity overshotbezier.
                row.tag = null
                row.animate().alpha(1f).setDuration(400).setInterpolator { Ease.overshotBezier.getInterpolation(it).coerceAtMost(1f) }.start()
            } else if (placed && laidOut && oldTop != y.toFloat()) {
                // NotificationLayoutAnimation: 200ms, y-translation outquint.
                row.translationY = oldTop - y
                row.animate().translationY(0f).setDuration(200).setInterpolator(Ease.outQuint).start()
            }
            y += row.measuredHeight
        }
        laidOut = true
    }
}

// ---------------------------------------------------------------------------------------------

abstract class Row(ctx: Context) : View(ctx) {
    protected val pressedBg = NineSlice(N9.bitmap(ctx, R.drawable.n9_event_pressed), 8, 8, 8, 8)
    protected var pressedNow = false
        set(v) { field = v; invalidate() }
    open val pressable = false
    private var downX = 0f
    private var downY = 0f

    abstract fun heightFor(width: Int): Int
    open fun onTap() {}

    // Held Today: the panel dims the whole view and draws the ✕ (see EventsPanel.drawChild); rows
    // only say whether they have one and where, and stop taking taps.
    private var editing = false
    open val dismissable: Boolean get() = false

    /** Where the ✕ sits, in this row's coordinates. */
    open fun badgeRect(): RectF {
        val size = N9.px(34f)
        val r = width - N9.px(14f) + N9.px(4f)
        return RectF(r - size, N9.px(2f), r, N9.px(2f) + size)
    }

    fun setEdit(on: Boolean, @Suppress("UNUSED_PARAMETER") animated: Boolean) { editing = on }

    companion object {
        private var close: android.graphics.Bitmap? = null
        fun closeBmp(ctx: Context) = close ?: N9.bitmap(ctx, R.drawable.n9_close_thumbnail).also { close = it }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, heightFor(w))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!pressable || editing) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; postDelayed(pressRunnable, 80) }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > N9.px(20f) || abs(e.y - downY) > N9.px(20f)) { removeCallbacks(pressRunnable); pressedNow = false }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(pressRunnable)
                if (abs(e.x - downX) <= N9.px(20f) && abs(e.y - downY) <= N9.px(20f)) {
                    pressedNow = true
                    postDelayed({ pressedNow = false }, 150) // minimum-tap-feedback-duration
                    onTap()
                } else pressedNow = false
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(pressRunnable); pressedNow = false }
        }
        return true
    }

    private val pressRunnable = Runnable { pressedNow = true }

    protected fun drawPressed(canvas: Canvas, r: RectF) {
        if (!pressedNow) return
        pressedBg.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        pressedBg.draw(canvas)
    }

    protected fun layout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()

    protected fun timeString(t: Long): String {
        val tf = DateFormat.getTimeFormat(context).format(Date(t))
        return if (DateUtils.isToday(t)) tf else DateFormat.getMediumDateFormat(context).format(Date(t)) + " " + tf
    }
}

interface ItemRow { var item: EventItem }

/**
 * DateStyle: padding-left 0.2mm, padding-top 0.2mm, padding-bottom 0.8mm, "%A".
 * EventsDayLabel: Light 4.0mm white, 4.6mm high. EventsDateLabel: Light 3.2mm #808080, 4mm high.
 * Tapping opens the calendar (DateStyle:pressed has the event-banner pressed background).
 */
class DateRow(ctx: Context) : Row(ctx) {
    private val day = TextPaint(N9.textPaint(N9.light, 40f, 0xFFFFFFFF.toInt()))
    private val date = TextPaint(N9.textPaint(N9.light, 32f, 0xFF808080.toInt()))
    override val pressable = true
    override fun heightFor(width: Int) = N9.ipx(2f + 46f + 40f + 8f)

    // The N9's weather widget (libevents-weather widget.qml): 120x87, right of the date, 1.4mm from
    // the edge; city bold 16px centred, a hairline at 28px, temperature 32px bottom-left, the
    // condition icon 48px bottom-right.
    private val wxCity = TextPaint(N9.textPaint(N9.bold, 16f, 0xFFFFFFFF.toInt()))
    private val wxTemp = TextPaint(N9.textPaint(N9.regular, 32f, 0xFFFFFFFF.toInt()))
    private val wxLine = android.graphics.Paint().apply { color = 0x29FFFFFF }
    private val wxPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val wxHighlight = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
    private val wx = RectF()
    private var tapOnWeather = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { tapOnWeather = Weather.hasData && wx.contains(e.x, e.y); invalidate() }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        val u = N9.px(1f)
        wx.set(width - N9.px(14f + 120f), N9.px(4f), width - N9.px(14f), N9.px(4f + 87f))
        if (pressedNow && tapOnWeather) canvas.drawRoundRect(wx, 8 * u, 8 * u, wxHighlight)
        else drawPressed(canvas, RectF(0f, 0f, width - N9.px(14f), height.toFloat()))
        if (Weather.hasData) {
            val cityW = wx.width() - 8 * u
            val c = TextUtils.ellipsize(Weather.city, wxCity, cityW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(c, wx.centerX() - wxCity.measureText(c) / 2f, wx.top + 19 * u, wxCity)
            canvas.drawRect(wx.left + 4 * u, wx.top + 28 * u, wx.right - 4 * u, wx.top + 29 * u, wxLine)
            canvas.drawText(Weather.temperature.toString() + "\u00B0", wx.left + 4 * u, wx.bottom - 16 * u, wxTemp)
            Weather.icon()?.let { res ->
                val ic = N9.bitmap(context, res)
                canvas.drawBitmap(ic, null, RectF(wx.right - 52 * u, wx.bottom - 52 * u, wx.right - 4 * u, wx.bottom - 4 * u), wxPaint)
            }
        }
        val now = Date()
        val dayText = DateFormat.format("EEEE", now).toString()
        // EventsDateLabel on the N9 read "21 June 2011" (day month year).
        val dateText = DateFormat.format("d MMMM yyyy", now).toString()
        val x = N9.px(2f)
        var top = N9.px(2f)
        fun line(p: TextPaint, s: String, boxH: Float) {
            val fm = p.fontMetrics
            canvas.drawText(s, x, top + boxH / 2f - (fm.ascent + fm.descent) / 2f, p)
            top += boxH
        }
        line(day, dayText, N9.px(46f))
        line(date, dateText, N9.px(40f))
    }

    override fun onTap() {
        if (tapOnWeather) { Weather.openApp(context); return }
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }
}

/** #CommonItemDividerInverted: margin-left 0.2mm, margin-right 1.4mm. */
class DividerRow(ctx: Context) : Row(ctx) {
    private val p = Paint().apply { color = 0xFF3C3C3C.toInt() }
    override fun heightFor(width: Int) = max(1, N9.ipx(1f))
    override fun onDraw(canvas: Canvas) = canvas.drawRect(N9.px(2f), 0f, width - N9.px(14f), height.toFloat(), p)
}

/** SwipeEventFeedArea MSeparatorStyle: 0.1mm. */
class SeparatorRow(ctx: Context) : Row(ctx) {
    private val p = Paint().apply { color = 0xFF262626.toInt() }
    override fun heightFor(width: Int) = max(1, N9.ipx(1f))
    override fun onDraw(canvas: Canvas) = canvas.drawRect(N9.px(2f), 0f, width - N9.px(14f), height.toFloat(), p)
}

/**
 * MLabelStyle#EventsViewHeaderLabel: bold 2.4mm #646464, margin-left 0.2mm, top 1.2mm, bottom 2.0mm.
 * MButtonStyle#NotificationAreaClearButton / #EventsButton: 16mm x 3.1mm, margin-top 1.2mm,
 * margin-right 1.4mm, FONT_SMALL_BOLD white, "meegotouch-home-button-background" 14 14 15 14;
 * pressed: #797979 text on the -pressed image.
 */
class SectionRow(ctx: Context, private val label: String, private val button: String, private val action: () -> Unit) : Row(ctx) {
    private val labelPaint = TextPaint(N9.textPaint(N9.bold, 24f, 0xFF646464.toInt()))
    private val btnPaint = TextPaint(N9.textPaint(N9.bold, N9.FONT_SMALL, 0xFFFFFFFF.toInt()))
    private val bg = Pill(Pill.Kind.NORMAL)
    private val bgPressed = Pill(Pill.Kind.PRESSED)
    private val bgDisabled = Pill(Pill.Kind.DISABLED)
    private val btn = RectF()
    private var down = false
        set(v) { field = v; invalidate() }
    var buttonEnabled = true
        set(v) { field = v; invalidate() }

    override fun heightFor(width: Int): Int {
        val fm = labelPaint.fontMetrics
        return (N9.px(12f) + (fm.descent - fm.ascent) + N9.px(20f)).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val fm = labelPaint.fontMetrics
        canvas.drawText(label, N9.px(2f), N9.px(12f) - fm.ascent, labelPaint)
        val bw = N9.px(160f); val bh = N9.px(31f)
        btn.set(width - N9.px(14f) - bw, N9.px(12f), width - N9.px(14f), N9.px(12f) + bh)
        val d = when { !buttonEnabled -> bgDisabled; down -> bgPressed; else -> bg }
        d.setBounds(btn.left.toInt(), btn.top.toInt(), btn.right.toInt(), btn.bottom.toInt())
        d.draw(canvas)
        btnPaint.color = if (down || !buttonEnabled) 0xFF797979.toInt() else 0xFFFFFFFF.toInt()
        val bfm = btnPaint.fontMetrics
        canvas.drawText(button, btn.centerX() - btnPaint.measureText(button) / 2f, btn.centerY() - (bfm.ascent + bfm.descent) / 2f, btnPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // reactive-margin-top 0.45mm, -bottom 1.65mm (a 6.4mm tall target)
        val hit = RectF(btn.left - N9.px(8f), btn.top - N9.px(4.5f), btn.right + N9.px(8f), btn.bottom + N9.px(16.5f))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (!hit.contains(e.x, e.y) || !buttonEnabled) return false; down = true }
            MotionEvent.ACTION_MOVE -> down = hit.contains(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                if (down) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); action() }
                down = false
            }
            MotionEvent.ACTION_CANCEL -> down = false
        }
        return true
    }
}

/**
 * Notification banner in the events view (MBannerStyle#FullEventBanner, #EventBanner margins 0.4mm):
 *  icon: 6.4mm, margin-top 1.0mm, margin-left 0.2mm
 *  title: FONT_FAMILY 2.2mm white, 2 lines, margin-top 1.0mm, left 1.6mm, right 1.4mm
 *  subtitle / timestamp: FONT_XXSMALL #C8C8C8, margin-top 0.2mm, timestamp margin-bottom 1.0mm
 *  portrait height 8mm .. 13.8mm
 */
class BannerRow(ctx: Context, item: EventItem) : Row(ctx), ItemRow {
    override var item: EventItem = item
        set(v) { field = v; requestLayout(); invalidate() }
    private val title = TextPaint(N9.textPaint(N9.regular, 22f, N9.COLOR_INVERTED_FOREGROUND))
    private val small = TextPaint(N9.textPaint(N9.regular, N9.FONT_XXSMALL, N9.COLOR_INVERTED_SECONDARY_FOREGROUND))
    private val iconPaint = Apps.iconPaint()
    private val margin = N9.px(4f)
    private var titleLayout: StaticLayout? = null
    override val pressable = true

    private val textX get() = N9.px(2f + 64f + 16f)

    override fun heightFor(width: Int): Int {
        val tw = (width - textX - N9.px(14f)).toInt()
        titleLayout = layout(item.title, title, tw, 2)
        val sfm = small.fontMetrics
        val line = sfm.descent - sfm.ascent
        var h = N9.px(10f) + titleLayout!!.height
        if (!item.subtitle.isNullOrEmpty()) h += N9.px(2f) + line
        h += N9.px(2f) + line + N9.px(10f)
        h = h.coerceIn(N9.px(80f), N9.px(138f))
        // + EventBanner margins, + NotificationAreaBannerLayoutPolicy vertical-spacing 0.8mm
        return (h + 2 * margin + N9.px(8f)).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val bottom = height - N9.px(8f) - margin
        drawPressed(canvas, RectF(0f, margin, width - N9.px(14f), bottom))
        item.icon?.let {
            val s = N9.px(64f)
            canvas.drawBitmap(it, null, RectF(N9.px(2f), margin + N9.px(10f), N9.px(2f) + s, margin + N9.px(10f) + s), iconPaint)
        }
        var y = margin + N9.px(10f)
        canvas.save(); canvas.translate(textX, y); titleLayout?.draw(canvas); canvas.restore()
        y += titleLayout?.height ?: 0
        val sfm = small.fontMetrics
        val avail = width - textX - N9.px(14f)
        item.subtitle?.takeIf { it.isNotEmpty() }?.let {
            y += N9.px(2f)
            canvas.drawText(TextUtils.ellipsize(it, small, avail, TextUtils.TruncateAt.END).toString(), textX, y - sfm.ascent, small)
            y += sfm.descent - sfm.ascent
        }
        y += N9.px(2f)
        canvas.drawText(timeString(item.time), textX, y - sfm.ascent, small)
    }

    override fun onTap() = EventStore.open(item)
    override val dismissable: Boolean get() = item.clearable

    /** On the icon's top right corner, as in Applications' edit mode, clear of the text. */
    override fun badgeRect(): RectF {
        val size = N9.px(34f)
        val cx = N9.px(2f + 64f - 6f); val cy = margin + N9.px(10f + 8f)
        return RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
    }
}

/**
 * SwipeEventFeedItem: padding-top/bottom 0.8mm, padding-right 1.4mm
 *  EventFeedIcon: 6.4mm, margin-left 0.2mm, margin-right 1.6mm (masked with meegotouch-home-event-icon-mask)
 *  title: bold 2.2mm white; body: 2.2mm white, up to 5 lines
 *  images: up to 3, small max 120px / single large max 168px, margin-top 1.6mm, margin-right 0.8mm
 *  footer: FONT_XXSMALL #808080, margin-top 0.4mm; separator "&#8226;" with 0.4mm margins
 */
class FeedRow(ctx: Context, item: EventItem) : Row(ctx), ItemRow {
    override var item: EventItem = item
        set(v) { field = v; requestLayout(); invalidate() }
    private val titleP = TextPaint(N9.textPaint(N9.bold, 22f, 0xFFFFFFFF.toInt()))
    private val bodyP = TextPaint(N9.textPaint(N9.regular, 22f, 0xFFFFFFFF.toInt()))
    private val footP = TextPaint(N9.textPaint(N9.regular, N9.FONT_XXSMALL, 0xFF808080.toInt()))
    private val iconPaint = Apps.iconPaint()
    private val overlayPaint = Apps.iconPaint().apply { alpha = (0.4f * 255).toInt() } // video-overlay-opacity
    private var titleL: StaticLayout? = null
    private var bodyL: StaticLayout? = null
    private val imageRect = RectF()
    override val pressable = true

    private val textX get() = N9.px(2f + 64f + 16f)

    override fun heightFor(width: Int): Int {
        val tw = (width - textX - N9.px(14f)).toInt()
        titleL = layout(item.title, titleP, tw, 2)
        bodyL = item.body?.let { layout(it, bodyP, tw, 5) } // preferred-line-count 5, "limit"
        var h = N9.px(8f) + titleL!!.height + (bodyL?.height ?: 0)
        item.image?.let { img ->
            val max = N9.px(168f)
            val k = min(max / img.width, max / img.height)
            h += N9.px(16f) + img.height * k
        }
        val ffm = footP.fontMetrics
        h += N9.px(4f) + (ffm.descent - ffm.ascent) + N9.px(8f)
        return max(h, N9.px(8f + 64f + 8f)).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        drawPressed(canvas, RectF(0f, 0f, width - N9.px(14f), height.toFloat()))
        item.icon?.let {
            val s = N9.px(64f)
            canvas.drawBitmap(it, null, RectF(N9.px(2f), N9.px(8f), N9.px(2f) + s, N9.px(8f) + s), iconPaint)
        }
        var y = N9.px(8f)
        canvas.save(); canvas.translate(textX, y); titleL?.draw(canvas); canvas.restore()
        y += titleL?.height ?: 0
        bodyL?.let { canvas.save(); canvas.translate(textX, y); it.draw(canvas); canvas.restore(); y += it.height }
        item.image?.let { img ->
            val max = N9.px(168f)
            val k = min(max / img.width, max / img.height)
            y += N9.px(16f)
            imageRect.set(textX, y, textX + img.width * k, y + img.height * k)
            canvas.drawBitmap(img, null, imageRect, iconPaint)
            y += img.height * k
        }
        val ffm = footP.fontMetrics
        y += N9.px(4f)
        val time = timeString(item.time)
        canvas.drawText(time, textX, y - ffm.ascent, footP)
        item.source?.let {
            var x = textX + footP.measureText(time) + N9.px(4f)
            canvas.drawText("•", x, y - ffm.ascent, footP)
            x += footP.measureText("•") + N9.px(4f)
            val avail = width - N9.px(14f) - x
            canvas.drawText(TextUtils.ellipsize(it, footP, avail, TextUtils.TruncateAt.END).toString(), x, y - ffm.ascent, footP)
        }
    }

    override fun onTap() = EventStore.open(item)
}

/**
 * Now playing, from the active media session: cover art, title, artist, a live position bar, and
 * the N9 lock-screen media controls (icon-m-lockscreen-mediacontrol-*). Laid out like an event banner.
 */
class MediaRow(ctx: Context) : Row(ctx) {
    private val titleP = TextPaint(N9.textPaint(N9.bold, 22f, N9.COLOR_INVERTED_FOREGROUND))
    private val subP = TextPaint(N9.textPaint(N9.regular, N9.FONT_XXSMALL, N9.COLOR_INVERTED_SECONDARY_FOREGROUND))
    private val timeP = TextPaint(N9.textPaint(N9.tabular, N9.FONT_XXSMALL, N9.COLOR_INVERTED_SECONDARY_FOREGROUND))
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3C3C3C.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = N9.COLOR_ACCENT1 }
    private val iconPaint = Apps.iconPaint()
    private val artClip = android.graphics.Path()
    private val icons = mapOf(
        "prev" to (N9.bitmap(ctx, R.drawable.n9_media_previous) to N9.bitmap(ctx, R.drawable.n9_media_previous_pressed)),
        "play" to (N9.bitmap(ctx, R.drawable.n9_media_play) to N9.bitmap(ctx, R.drawable.n9_media_play_pressed)),
        "pause" to (N9.bitmap(ctx, R.drawable.n9_media_pause) to N9.bitmap(ctx, R.drawable.n9_media_pause_pressed)),
        "next" to (N9.bitmap(ctx, R.drawable.n9_media_next) to N9.bitmap(ctx, R.drawable.n9_media_next_pressed)),
    )
    private val buttons = LinkedHashMap<String, RectF>()
    private var downButton: String? = null
    private var downOnArt = false

    companion object {
        const val ART = 112f
        const val BTN = 64f      // touch target
        const val BTN_ICON = 36f
    }

    private val textX get() = N9.px(2f + ART + 16f)

    override fun heightFor(width: Int): Int {
        val tf = titleP.fontMetrics; val sf = subP.fontMetrics
        val text = (tf.descent - tf.ascent) + N9.px(2f) + (sf.descent - sf.ascent) + N9.px(14f) + N9.px(4f) + N9.px(6f) +
            (sf.descent - sf.ascent) + N9.px(BTN)
        return (N9.px(8f) + max(N9.px(ART), text) + N9.px(16f)).toInt()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDraw(canvas: Canvas) {
        val top = N9.px(8f)
        val a = N9.px(ART)
        val artRect = RectF(N9.px(2f), top, N9.px(2f) + a, top + a)
        canvas.drawRect(artRect, track)
        MediaWatcher.art?.let { bmp ->
            artClip.reset(); artClip.addRoundRect(artRect, N9.px(6f), N9.px(6f), android.graphics.Path.Direction.CW)
            canvas.save(); canvas.clipPath(artClip)
            // centre-crop the cover into the square
            val s = min(bmp.width, bmp.height)
            val src = android.graphics.Rect((bmp.width - s) / 2, (bmp.height - s) / 2, (bmp.width + s) / 2, (bmp.height + s) / 2)
            canvas.drawBitmap(bmp, src, artRect, iconPaint)
            canvas.restore()
        } ?: MediaWatcher.pkg?.let { p -> Apps.installed.firstOrNull { it.pkg == p }?.icon }?.let { ic ->
            val i = a * 0.6f
            canvas.drawBitmap(ic, null, RectF(artRect.centerX() - i / 2, artRect.centerY() - i / 2, artRect.centerX() + i / 2, artRect.centerY() + i / 2), iconPaint)
        }

        val right = width - N9.px(14f)
        val w = right - textX
        var y = top
        val tf = titleP.fontMetrics
        canvas.drawText(TextUtils.ellipsize(MediaWatcher.title.orEmpty(), titleP, w, TextUtils.TruncateAt.END).toString(), textX, y - tf.ascent, titleP)
        y += tf.descent - tf.ascent + N9.px(2f)
        val sf = subP.fontMetrics
        canvas.drawText(TextUtils.ellipsize(MediaWatcher.artist.orEmpty(), subP, w, TextUtils.TruncateAt.END).toString(), textX, y - sf.ascent, subP)
        y += sf.descent - sf.ascent + N9.px(14f)

        // Position bar (4px) and times.
        val dur = MediaWatcher.duration
        val pos = MediaWatcher.position
        val barH = N9.px(4f)
        canvas.drawRoundRect(textX, y, right, y + barH, barH / 2, barH / 2, track)
        if (dur > 0) canvas.drawRoundRect(textX, y, textX + w * (pos.toFloat() / dur).coerceIn(0f, 1f), y + barH, barH / 2, barH / 2, fill)
        y += barH + N9.px(6f)
        val tm = timeP.fontMetrics
        canvas.drawText(fmt(pos), textX, y - tm.ascent, timeP)
        if (dur > 0) { val d = fmt(dur); canvas.drawText(d, right - timeP.measureText(d), y - tm.ascent, timeP) }
        y += tm.descent - tm.ascent

        // Controls, centred under the text.
        buttons.clear()
        val b = N9.px(BTN)
        val cx = textX + w / 2f
        for ((i, k) in listOf("prev", if (MediaWatcher.playing) "pause" else "play", "next").withIndex()) {
            val l = cx + (i - 1.5f) * b
            buttons[k] = RectF(l, y, l + b, y + b)
        }
        val ic = N9.px(BTN_ICON)
        for ((k, r) in buttons) {
            val (normal, pressed) = icons.getValue(k)
            canvas.drawBitmap(if (downButton == k) pressed else normal, null,
                RectF(r.centerX() - ic / 2, r.centerY() - ic / 2, r.centerX() + ic / 2, r.centerY() + ic / 2), iconPaint)
        }

        // Keep the time moving while playing.
        if (MediaWatcher.playing && isShown) postInvalidateDelayed(500)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downButton = buttons.entries.firstOrNull { it.value.contains(e.x, e.y) }?.key
                downOnArt = downButton == null && e.x < textX
                if (downButton == null && !downOnArt) return false
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val k = downButton
                if (k != null && buttons[k]?.contains(e.x, e.y) != true) { downButton = null; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                when (downButton) {
                    "prev" -> MediaWatcher.previous()
                    "play", "pause" -> MediaWatcher.playPause()
                    "next" -> MediaWatcher.next()
                    null -> if (downOnArt) MediaWatcher.open()
                }
                if (downButton != null) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                downButton = null; downOnArt = false; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { downButton = null; downOnArt = false; invalidate() }
        }
        return true
    }
}

/** "No notifications or feeds" (qtn_home_events_empty). */
class EmptyRow(ctx: Context) : Row(ctx) {
    private val p = TextPaint(N9.textPaint(N9.light, N9.FONT_LARGE, 0xFF646464.toInt()))
    override fun heightFor(width: Int) = N9.ipx(200f)
    override fun onDraw(canvas: Canvas) {
        val s = context.getString(R.string.qtn_home_events_empty)
        val avail = width - N9.px(14f)
        val fm = p.fontMetrics
        canvas.drawText(s, (avail - p.measureText(s)) / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, p)
    }
}
