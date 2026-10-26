package com.amiigo.harmattan

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The Harmattan home: a ring of full-screen views (Launcher → Switcher → Events → Launcher ...)
 * dragged 1:1 under the finger. Settling uses SwipePannableViewportStyle#SwipePage:
 * return-animation-duration 250, return-animation-easing-curve outexpo.
 * A flick (blanco MSwipeRecognizerStyle: >= 5mm within 300ms) moves to the next view.
 */
class SwipeHome(ctx: Context) : ViewGroup(ctx) {

    private val pages = ArrayList<View>()
    var offset = 0f
        private set
    private var anim: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var startOffset = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    var pagingEnabled = true

    /** Gap between pages (local px) and whether neighbours are shown: the zoomed-out Home settings. */
    var gap = 0f
        set(v) { field = v; position() }
    var showNeighbours = false
        set(v) {
            field = v
            for (p in pages) {
                p.clipToOutline = v
                p.outlineProvider = if (v) object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, o: android.graphics.Outline) =
                        o.setRoundRect(0, 0, view.width, view.height, N9.px(14f) / 0.6f)
                } else android.view.ViewOutlineProvider.BACKGROUND
            }
            position()
        }
    var onPageSettled: ((Int) -> Unit)? = null
    var onOffsetChanged: ((Float) -> Unit)? = null

    val current: Int get() = mod(offset.roundToInt(), pages.size)

    fun addPage(v: View) {
        pages += v
        addView(v)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        for (p in pages) p.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (p in pages) p.layout(0, 0, r - l, b - t)
        position()
    }

    private fun mod(a: Int, n: Int) = ((a % n) + n) % n

    private fun position() {
        val n = pages.size
        if (n == 0) return
        val w = width.toFloat()
        for ((i, p) in pages.withIndex()) {
            var d = (i - offset) % n
            if (d < -n / 2f) d += n
            if (d >= n / 2f) d -= n
            p.translationX = d * (w + gap)
            p.visibility = if (abs(d) < (if (showNeighbours) 2f else 1f)) View.VISIBLE else View.INVISIBLE
        }
        onOffsetChanged?.invoke(offset)
    }

    fun snapTo(page: Int, animated: Boolean) {
        val n = pages.size
        // Choose the nearest representative of `page` on the ring.
        val cur = offset
        var target = page.toFloat()
        while (target - cur > n / 2f) target -= n
        while (cur - target > n / 2f) target += n
        animateTo(target, animated)
    }

    private fun animateTo(target: Float, animated: Boolean) {
        anim?.cancel()
        if (!animated || width == 0) {
            offset = normalize(target); position(); onPageSettled?.invoke(current); return
        }
        val a = ValueAnimator.ofFloat(offset, target)
        a.duration = 250
        a.interpolator = Ease.outExpo
        a.addUpdateListener { offset = it.animatedValue as Float; position() }
        a.addListener(object : AnimatorListenerAdapter() {
            var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                offset = normalize(target); position()
                onPageSettled?.invoke(current)
            }
        })
        anim = a
        a.start()
    }

    private fun normalize(o: Float): Float {
        val n = pages.size
        var r = o % n
        if (r < 0) r += n
        return r
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!pagingEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downTime = ev.eventTime
                tracker?.recycle(); tracker = VelocityTracker.obtain(); tracker?.addMovement(ev)
                dragging = false
                if (anim?.isRunning == true) {
                    // Catch the views mid-flight.
                    anim?.cancel(); startOffset = offset; dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (ev.pointerCount > 1) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > N9.px(PannableView.PAN_THRESHOLD) && abs(dx) > abs(dy)) {
                    dragging = true
                    // Start moving from where the threshold was crossed, without a jump.
                    startOffset = offset + (if (dx > 0) 1 else -1) * N9.px(PannableView.PAN_THRESHOLD) / width.coerceAtLeast(1)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> return false
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!pagingEnabled) return false
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downTime = ev.eventTime; startOffset = offset; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = ev.x - downX
                    if (abs(dx) > N9.px(PannableView.PAN_THRESHOLD)) {
                        dragging = true
                        startOffset = offset + (if (dx > 0) 1 else -1) * N9.px(PannableView.PAN_THRESHOLD) / width
                    }
                    else return true
                }
                offset = startOffset - (ev.x - downX) / width
                position()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) release(ev)
                dragging = false
                tracker?.recycle(); tracker = null
            }
        }
        return true
    }

    private fun release(ev: MotionEvent) {
        val t = tracker
        t?.computeCurrentVelocity(1000)
        val vx = t?.xVelocity ?: 0f
        val dx = ev.x - downX
        val dt = ev.eventTime - downTime
        val swipe = (dt <= 300 && abs(dx) >= N9.px(50f)) || abs(vx) > N9.px(50f) / 0.3f
        val target = when {
            swipe && (vx < 0 || (vx == 0f && dx < 0)) -> ceil(offset - 0.001f)
            swipe -> floor(offset + 0.001f)
            else -> offset.roundToInt().toFloat()
        }
        animateTo(target, true)
    }
}
