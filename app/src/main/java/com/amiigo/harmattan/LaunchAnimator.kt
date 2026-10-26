package com.amiigo.harmattan

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Opening an app is left to the system (its own window animation, from the icon's position).
 *
 * Closing: back on home, the app's window card shrinks from the screen into its switcher tile —
 * the N9 compositor's minimise-to-switcher, with the switcher's incoming curve
 * (SwipeSwitcherButtonStyle incoming-animation 400ms overshotbezier).
 */
class LaunchAnimator(private val act: Activity, private val root: FrameLayout, private val home: View) {
    companion object {
        const val CLOSE_MS = 400L
        const val HOME_ZOOM = 1.12f
        /** Longest wait for the system's own home transition before shrinking anyway. */
        const val MAX_WAIT_MS = 150L
        /** Where One UI's swipe-home animation leaves the app window (fraction of screen width / height). */
        const val HANDOFF_SCALE = 0.27f
        const val HANDOFF_Y = 0.56f
        /** Decelerating with a small settle into the tile; spreads the motion over the whole duration. */
        private val SHRINK = Ease.outBack(0.8f)
    }

    private val overlay = WindowOverlay(act)
    private var anim: ValueAnimator? = null
    var lastOpened: AppEntry? = null
        private set

    init {
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.visibility = View.GONE
    }

    private fun rectInRoot(v: View, r: Rect): RectF {
        val a = IntArray(2); v.getLocationInWindow(a)
        val b = IntArray(2); root.getLocationInWindow(b)
        // Include the view's own scale (switcher tiles may still be settling).
        val sx = v.scaleX; val sy = v.scaleY
        val l = a[0] - b[0] + r.left * sx
        val t = a[1] - b[1] + r.top * sy
        return RectF(l, t, l + r.width() * sx, t + r.height() * sy)
    }

    private fun screen() = RectF(0f, 0f, root.width.toFloat(), root.height.toFloat())

    /** Start [e]; [src]/[srcRect] (in src's coordinates) tell the system where it opens from. */
    fun open(e: AppEntry, src: View, srcRect: Rect) {
        lastOpened = e
        val sl = IntArray(2); src.getLocationOnScreen(sl)
        val k = screenScale(src) // the Applications panel may be drawn larger or smaller
        val bounds = Rect(sl[0] + (srcRect.left * k).toInt(), sl[1] + (srcRect.top * k).toInt(),
            sl[0] + (srcRect.right * k).toInt(), sl[1] + (srcRect.bottom * k).toInt())
        Apps.launch(e, bounds, null)
    }

    /** Home is gone (app on screen): put everything back in place, invisibly. */
    fun reset() {
        anim?.cancel(); anim = null
        pendingClose = null
        prepared = null
        overlay.visibility = View.GONE
        home.scaleX = 1f; home.scaleY = 1f; home.alpha = 1f
        home.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private var pendingClose: (() -> Unit)? = null
    private var prepared: AppEntry? = null
    private val targetRect = RectF()

    /**
     * Get home into its "app on top" state while it's hidden: zoomed and dimmed around [tile], the tile
     * hidden, the (still invisible) card ready. Done in onStop, so when the system brings home back the
     * first frame is already right and nothing jumps.
     */
    fun prepare(e: AppEntry, tile: View) {
        anim?.cancel()
        home.animate().cancel()
        home.scaleX = 1f; home.scaleY = 1f
        // Where the tile sits at normal scale. Pivoting home on its centre keeps that point still while
        // home un-zooms, so this is exactly where the card lands.
        val a = IntArray(2); tile.getLocationInWindow(a)
        val b = IntArray(2); root.getLocationInWindow(b)
        val l = (a[0] - b[0]).toFloat(); val t = (a[1] - b[1]).toFloat()
        targetRect.set(l, t, l + tile.width, t + tile.height)
        home.pivotX = targetRect.centerX() - home.left
        home.pivotY = targetRect.centerY() - home.top
        home.scaleX = HOME_ZOOM; home.scaleY = HOME_ZOOM; home.alpha = 0.25f
        tile.alpha = 0f
        overlay.setup(e)
        overlay.rect.set(screen()); overlay.corner = 0f
        // Invisible while the system brings home in: a static full-screen card there reads as a freeze.
        overlay.cardAlpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.invalidate()
        prepared = e
    }

    /**
     * Shrink [e]'s window card into [target]. Starts at [playClose] — when the home window's own
     * entrance transition is over — or after [MAX_WAIT_MS], whichever comes first.
     */
    fun close(e: AppEntry, target: View, onEnd: () -> Unit = {}): Boolean {
        if (prepared?.key != e.key) prepare(e, target)
        pendingClose = { runClose(target, onEnd) }
        overlay.postDelayed({ playClose() }, MAX_WAIT_MS)
        return true
    }

    fun playClose() {
        val p = pendingClose ?: return
        pendingClose = null
        p()
    }

    private fun runClose(target: View, onEnd: () -> Unit) {
        prepared = null
        // Where the tile really is now (the switcher may have reordered since prepare): land there.
        val a = IntArray(2); target.getLocationInWindow(a)
        val b = IntArray(2); root.getLocationInWindow(b)
        val hs = home.scaleX
        val px = home.left + home.pivotX; val py = home.top + home.pivotY
        // Undo home's zoom about its pivot to get the tile's place at normal scale.
        val l = px + (a[0] - b[0] - px) / hs; val t = py + (a[1] - b[1] - py) / hs
        if (target.width > 0) targetRect.set(l, t, l + target.width, t + target.height)
        val to = RectF(targetRect)
        // Pick up where the system's own swipe-home animation leaves the app window: shrunk to about a
        // quarter of the screen width just below the middle, fading out. From there the card carries
        // on into the tile, so it reads as one motion rather than a second minimise.
        val s = screen()
        val w = s.width() * HANDOFF_SCALE
        val h = w * s.height() / s.width()
        val cx = s.centerX(); val cy = s.height() * HANDOFF_Y
        val from = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        // The home layer is big; render it once into a GPU layer while it scales and fades.
        home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CLOSE_MS
            interpolator = Ease.linear
            addUpdateListener {
                val t = it.animatedFraction
                val g = SHRINK.getInterpolation(t)
                overlay.rect.set(lerp(from.left, to.left, g), lerp(from.top, to.top, g), lerp(from.right, to.right, g), lerp(from.bottom, to.bottom, g))
                overlay.corner = N9.px(6f)
                overlay.cardAlpha = min(1f, t / 0.2f)
                overlay.invalidate()
                val h = Ease.outQuint.getInterpolation(t)
                home.scaleX = lerp(HOME_ZOOM, 1f, h); home.scaleY = home.scaleX
                home.alpha = lerp(0.25f, 1f, h)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    target.alpha = 1f
                    overlay.visibility = View.GONE
                    home.scaleX = 1f; home.scaleY = 1f; home.alpha = 1f
                    home.setLayerType(View.LAYER_TYPE_NONE, null)
                    onEnd()
                }
            })
            start()
        }
    }

    /** Return with no tile to land in: home zooms back out from behind the app. */
    fun homeZoomOut() {
        anim?.cancel()
        prepared = null
        overlay.visibility = View.GONE
        home.pivotX = home.width / 2f; home.pivotY = home.height / 2f
        home.scaleX = HOME_ZOOM; home.scaleY = HOME_ZOOM; home.alpha = 0.25f
        home.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(CLOSE_MS).setInterpolator(Ease.outExpo).start()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/** Draws an app's window card at [rect] with rounded [corner]s. */
class WindowOverlay(ctx: Context) : View(ctx) {
    val rect = RectF()
    var corner = 0f
    var cardAlpha = 1f
    private var entry: AppEntry? = null
    private val clip = Path()

    fun setup(e: AppEntry) { entry = e }

    override fun onDraw(canvas: Canvas) {
        val e = entry ?: return
        if (cardAlpha <= 0f) return
        clip.reset()
        clip.addRoundRect(rect, corner, corner, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val snap = Previews.get(e)
        if (snap != null) Previews.draw(canvas, rect, snap, (cardAlpha * 255).toInt())
        else WindowArt.drawCard(canvas, rect, e, (cardAlpha * 255).toInt())
        canvas.restore()
    }
}
