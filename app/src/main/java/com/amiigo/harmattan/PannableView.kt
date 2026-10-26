package com.amiigo.harmattan

import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Vertical kinetic scrolling with MeeGo Touch physics (MPhysics2DPanning::integrateAxis,
 * libmeegotouchcore.so.0.25.9 @ 0x43fb6c28, decoded from ARM), parameters from
 * mpannablewidgetstyle.css:
 *
 *   sliding-friction-c 0.965, border-spring-k 0.06, border-friction-c 0.66,
 *   maximum-velocity 150, maximum-velocity-for-press 2.0
 *
 * Per 60 Hz tick, after release:
 *   inside range:  v *= slidingFriction; pos += v
 *   past an edge:  v' = v + k * (edge - pos)
 *                  f  = borderFriction if v already heads back to the edge, else slidingFriction
 *                  if pos + v'·f crosses the edge -> snap to edge, v = 0; else v = f·v'; pos += v
 * While the finger is down past an edge, content moves at half the finger speed.
 */
open class PannableView(ctx: Context) : ViewGroup(ctx) {

    companion object {
        const val SLIDING_FRICTION = 0.965f
        const val BORDER_SPRING_K = 0.06f
        const val BORDER_FRICTION = 0.66f
        const val MAX_VELOCITY = 150f          // N9 px / tick
        const val MAX_VELOCITY_FOR_PRESS = 2f  // N9 px / tick
        const val PAN_THRESHOLD = 20f          // blanco MPanRecognizerStyle distance-threshold: 2.0mm
        const val TICK_MS = 1000f / 60f
        const val INDICATOR_HIDE_MS = 1500L    // MPositionIndicatorStyle hide-timeout
        const val TOP_STRETCH = 34f            // furthest the content follows a pull down at the top (N9 px)
        const val SHADE_PULL = 110f            // pulling this far past the top opens the notification panel
    }

    var content: View? = null
        set(v) {
            field?.let { removeView(it) }
            field = v
            if (v != null) addView(v)
        }

    /** Extra space kept clear at the bottom (navigation bar). */
    var bottomInset = 0
        set(v) { field = v; requestLayout() }

    var pos = 0f
        private set
    private var vel = 0f
    private var pressed = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var tracker: VelocityTracker? = null
    private var running = false
    private var lastFrameNs = 0L
    private var acc = 0f
    private var prevPos = 0f

    var onScrollChanged: ((Float) -> Unit)? = null
    var panningEnabled = true

    // Position indicator: "meegotouch-positionindicator-indicator" 0 0 2 2, 6px wide, min 2mm.
    private val indicator = NineSlice(N9.bitmap(ctx, R.drawable.n9_position_indicator), 0, 0, 2, 2)
    private var indicatorAlpha = 0f
    private var lastMoveTime = 0L

    init {
        setWillNotDraw(false)
        clipChildren = true
    }

    val maxScroll: Float
        get() {
            val c = content ?: return 0f
            return max(0f, (c.measuredHeight + bottomInset - height).toFloat())
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        content?.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        if (!pressed && !running) pos = pos.coerceIn(0f, maxScroll)
        apply()
    }

    private var glide: android.animation.ValueAnimator? = null

    /**
     * Back to the top. A timed glide rather than a fling: the N9's maximum fling speed can't cover a
     * long list, so a fling from far down would stop short. Longer distances take a little longer.
     */
    fun scrollToTop(animated: Boolean) {
        stop()
        if (!animated || pos <= 0f) { pos = 0f; apply(); return }
        val from = pos
        val screens = from / height.coerceAtLeast(1)
        glide = android.animation.ValueAnimator.ofFloat(from, 0f).apply {
            duration = (300 + 120 * kotlin.math.sqrt(screens)).toLong().coerceAtMost(750)
            interpolator = Ease.outQuint
            addUpdateListener { pos = it.animatedValue as Float; lastMoveTime = System.currentTimeMillis(); indicatorAlpha = 1f; apply() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { glide = null; scheduleIndicatorFade() }
            })
            start()
        }
    }

    /** Programmatic pan (used by launcher drag-and-drop near the edges). Returns the applied delta. */
    fun nudge(dy: Float): Float {
        val old = pos
        pos = (pos + dy).coerceIn(0f, maxScroll)
        if (pos != old) { lastMoveTime = System.currentTimeMillis(); indicatorAlpha = 1f; apply(); scheduleIndicatorFade() }
        return pos - old
    }

    fun stop() {
        running = false
        vel = 0f
        glide?.cancel(); glide = null
    }

    // Pulling down at the top: a short, firm stretch, and past it Android's notification panel.
    private var startedAtTop = false
    private var pullOver = 0f
    private var shadeOpened = false

    /** Draws at [shown] (the physics position, or one between two 60 Hz physics steps). */
    private fun apply(shown: Float = pos) {
        content?.translationY = -shown
        onScrollChanged?.invoke(shown)
        invalidate()
    }

    // ---- touch ----

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!panningEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                track(ev, reset = true)
                downX = ev.x; downY = ev.y; lastY = ev.y
                dragging = false
                pressed = true
                val fast = running && abs(vel) > MAX_VELOCITY_FOR_PRESS * N9.s
                val outside = pos < 0f || pos > maxScroll
                stop()
                if (fast || outside) {
                    // A press on moving content only stops it (maximum-velocity-for-press).
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (!dragging && claim(ev)) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                if (!running) settleIfOutside()
            }
        }
        return dragging
    }

    private fun claim(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 1) return false
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) > N9.px(PAN_THRESHOLD) && abs(dy) > abs(dx)) {
            dragging = true
            startedAtTop = pos <= 0f
            pullOver = -pos.coerceAtMost(0f)
            shadeOpened = false
            lastY = ev.y
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!panningEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                track(ev, reset = true)
                downX = ev.x; downY = ev.y; lastY = ev.y
                pressed = true
                stop()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (!dragging) { claim(ev); return true }
                val diff = lastY - ev.y
                lastY = ev.y
                val maxS = maxScroll
                if (pos <= 0f && (diff < 0f || pullOver > 0f)) {
                    // Above the top: the finger's travel past it, eased into a stretch of at most TOP_STRETCH.
                    pullOver = (pullOver - diff).coerceAtLeast(0f)
                    val lim = N9.px(TOP_STRETCH)
                    pos = -lim * (1f - kotlin.math.exp(-pullOver / (lim * 2f)))
                    if (pullOver <= 0f) pos = 0f
                    if (startedAtTop && !shadeOpened && pullOver > N9.px(SHADE_PULL)) {
                        shadeOpened = true
                        performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
                        expandShade(context, settings = Apps.pullOpensQuickSettings)
                    }
                } else pos += if (pos < 0f || pos > maxS) diff * 0.5f else diff
                lastMoveTime = System.currentTimeMillis()
                indicatorAlpha = 1f
                apply()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                track(ev)
                pressed = false
                if (dragging && pos < 0f) {
                    vel = 0f; start() // spring back from the stretch
                } else if (dragging) {
                    val t = tracker
                    t?.computeCurrentVelocity(1000)
                    val vy = t?.yVelocity ?: 0f
                    val cap = MAX_VELOCITY * N9.s
                    vel = (-vy / 60f).coerceIn(-cap, cap)
                    start()
                } else settleIfOutside()
                dragging = false
                tracker?.recycle(); tracker = null
            }
        }
        return true
    }

    private fun settleIfOutside() {
        if (pos < 0f || pos > maxScroll) start()
    }

    private fun track(ev: MotionEvent, reset: Boolean = false) {
        if (reset) { tracker?.recycle(); tracker = VelocityTracker.obtain() }
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(ev)
    }

    // ---- integration ----

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            if (lastFrameNs != 0L) acc += (now - lastFrameNs) / 1_000_000f
            lastFrameNs = now
            var steps = 0
            while (acc >= TICK_MS && steps < 8) { prevPos = pos; tick(); acc -= TICK_MS; steps++ }
            if (steps == 8) acc = 0f
            lastMoveTime = System.currentTimeMillis()
            indicatorAlpha = 1f
            // The N9 physics steps at 60 Hz; on faster screens draw in between steps so every frame moves.
            apply(prevPos + (pos - prevPos) * (acc / TICK_MS).coerceIn(0f, 1f))
            val inRange = pos >= 0f && pos <= maxScroll
            if (inRange && abs(vel) < 0.5f * N9.s) {
                running = false
                vel = 0f
                apply()
                scheduleIndicatorFade()
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        acc = TICK_MS
        prevPos = pos
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun tick() {
        val start = 0f
        val end = maxScroll
        when {
            pos < start -> {
                val v2 = vel + BORDER_SPRING_K * (start - pos)
                val f = if (vel > 0f) BORDER_FRICTION else SLIDING_FRICTION
                if (pos + v2 * f >= start) { pos = start; vel = 0f } else { vel = f * v2; pos += vel }
            }
            pos > end -> {
                val v2 = vel + BORDER_SPRING_K * (end - pos)
                val f = if (vel < 0f) BORDER_FRICTION else SLIDING_FRICTION
                if (pos + v2 * f <= end) { pos = end; vel = 0f } else { vel = f * v2; pos += vel }
            }
            else -> { vel *= SLIDING_FRICTION; pos += vel }
        }
    }

    // ---- position indicator ----

    private fun scheduleIndicatorFade() {
        postDelayed({
            if (!running && !dragging && System.currentTimeMillis() - lastMoveTime >= INDICATOR_HIDE_MS - 20) {
                animate().cancel()
                fadeIndicator()
            }
        }, INDICATOR_HIDE_MS)
    }

    private fun fadeIndicator() {
        val a = android.animation.ValueAnimator.ofFloat(indicatorAlpha, 0f)
        a.duration = 250
        a.interpolator = Ease.outExpo
        a.addUpdateListener { indicatorAlpha = it.animatedValue as Float; invalidate() }
        a.start()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val c = content ?: return
        val total = c.measuredHeight + bottomInset
        if (indicatorAlpha <= 0f || total <= height || !Apps.showScrollbars) return
        val margin = N9.px(4f) // 0.4mm
        val track = height - bottomInset - 2 * margin - N9.px(N9.STATUSBAR)
        val minH = N9.px(20f) // min-indicator-size: 2mm
        var h = max(minH, track * height / total)
        var y = N9.px(N9.STATUSBAR) + margin + (track - h) * (pos / max(1f, maxScroll))
        if (pos < 0f) { h = max(minH, h + pos); y = N9.px(N9.STATUSBAR) + margin }
        if (pos > maxScroll) { val over = pos - maxScroll; h = max(minH, h - over); y = N9.px(N9.STATUSBAR) + margin + track - h }
        val w = N9.px(6f)
        indicator.setBounds((width - margin - w).toInt(), y.toInt(), (width - margin).toInt(), (y + h).toInt())
        indicator.alpha = (indicatorAlpha * 255).toInt()
        indicator.draw(canvas)
    }
}
