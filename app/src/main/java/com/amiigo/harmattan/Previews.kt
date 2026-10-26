package com.amiigo.harmattan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Live previews for the Open apps tiles: Android's own snapshot of each task (the picture its Recents
 * screen shows, taken when you leave an app), read through Shizuku. Without Shizuku, or with the
 * option off, tiles keep the app cards.
 */
object Previews {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val cache = HashMap<Int, Bitmap>() // by task id
    @Volatile private var pending = false

    val enabled: Boolean get() = Apps.livePreviews && Apps.matchRecents && SystemRecents.granted()

    fun get(e: AppEntry): Bitmap? {
        if (!Apps.livePreviews) return null
        val id = Apps.taskIdFor(e) ?: return null
        return synchronized(cache) { cache[id] }
    }

    /** Fetch fresh snapshots for [entries] off the main thread; [done] runs on the main thread after. */
    fun refresh(entries: List<AppEntry>, done: () -> Unit) {
        if (!enabled) {
            val had = synchronized(cache) { cache.isNotEmpty().also { cache.clear() } }
            if (had) done()
            return
        }
        if (pending) return
        pending = true
        val ids = entries.mapNotNull { Apps.taskIdFor(it) }
        exec.execute {
            val fresh = HashMap<Int, Bitmap>()
            for (id in ids) SystemRecents.snapshot(id, MAX_W)?.let { fresh[id] = it }
            synchronized(cache) {
                cache.keys.retainAll(ids.toSet())
                cache.putAll(fresh)
            }
            pending = false
            main.post(done)
        }
    }

    /** Snapshots are kept at about a large tile's width on screen. */
    private const val MAX_W = 540

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val black = Paint().apply { color = 0xFF000000.toInt() }

    /** [snap] filling [r] edge to edge from the top (the N9 tiles showed the window from its top). */
    fun draw(canvas: Canvas, r: RectF, snap: Bitmap, alpha: Int = 255) {
        paint.alpha = alpha
        black.alpha = alpha
        canvas.drawRect(r, black)
        val k = r.width() / snap.width
        val visibleH = minOf(snap.height.toFloat(), r.height() / k)
        canvas.drawBitmap(snap, Rect(0, 0, snap.width, visibleH.toInt()), RectF(r.left, r.top, r.right, r.top + visibleH * k), paint)
    }
}
