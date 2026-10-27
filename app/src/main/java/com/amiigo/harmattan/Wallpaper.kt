package com.amiigo.harmattan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.view.View
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A wallpaper behind home (Customize Today). The N9 home was black; this is an option on top of it:
 * sharp behind Today, with a black gradient at the top so the date and weather stay readable and a
 * darkness behind the notifications and feed, and blurred behind Applications and Recents.
 */
object Wallpaper {
    private fun file(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")
    fun exists(ctx: Context) = file(ctx).exists()
    fun stamp(ctx: Context) = file(ctx).lastModified()

    /** Darkness behind the notifications and feed, 0..1. */
    fun dim(ctx: Context) = prefs(ctx).getFloat("wallpaper_dim", 0.4f)
    fun setDim(ctx: Context, f: Float) = prefs(ctx).edit().putFloat("wallpaper_dim", f).apply()
    /** Blur behind Applications and Recents, 0..1 (of [MAX_BLUR]). */
    fun blur(ctx: Context) = prefs(ctx).getFloat("wallpaper_blur", 0.6f)
    fun setBlur(ctx: Context, f: Float) = prefs(ctx).edit().putFloat("wallpaper_blur", f).apply()
    /** The wallpaper scrolls along, endlessly, as you swipe between views. */
    fun parallax(ctx: Context) = prefs(ctx).getBoolean("wallpaper_parallax", true)
    fun setParallax(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wallpaper_parallax", on).apply()
    /** How far it scrolls for each view swiped, as a share of the screen's width. */
    const val PARALLAX = 0.25f
    /** The dark shade at the top of Today, behind the date and weather. */
    fun topShade(ctx: Context) = prefs(ctx).getBoolean("wallpaper_top_shade", true)
    fun setTopShade(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wallpaper_top_shade", on).apply()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    const val MAX_BLUR = 90f // device px

    /**
     * Copy the chosen picture in, scaled to the screen. Blocking. The whole picture is read first and
     * checked, then written beside the old one and swapped in, so a short read or a view reloading
     * mid-write can never leave a half picture (it used to be cut off with a black bottom).
     */
    fun save(ctx: Context, uri: Uri): Boolean = runCatching {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val dm = ctx.resources.displayMetrics
        val target = max(dm.widthPixels, dm.heightPixels)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return false
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val tmp = File(ctx.filesDir, "wallpaper.tmp")
        tmp.outputStream().use { if (!b.compress(Bitmap.CompressFormat.JPEG, 92, it)) return false }
        tmp.renameTo(file(ctx))
    }.getOrDefault(false)

    fun remove(ctx: Context) { file(ctx).delete() }

    /** Also make it Android's home wallpaper, so Recents (and anything else showing it) matches. */
    fun shareWithSystem(ctx: Context) = prefs(ctx).getBoolean("wallpaper_share_system", false)
    fun setShareWithSystem(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wallpaper_share_system", on).apply()

    /**
     * Hand the picture to Android as its home-screen wallpaper (the lock screen keeps its own).
     * Blocking; false if there's no picture or Android refused.
     */
    fun applyToSystem(ctx: Context): Boolean = runCatching {
        val b = load(ctx) ?: return false
        android.app.WallpaperManager.getInstance(ctx).setBitmap(b, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
        true
    }.getOrDefault(false)

    fun load(ctx: Context): Bitmap? = file(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** [b] drawn to cover [dst], centred. */
    fun drawCover(c: Canvas, b: Bitmap, dst: Rect, p: Paint) {
        val k = max(dst.width() / b.width.toFloat(), dst.height() / b.height.toFloat())
        val w = (dst.width() / k).toInt(); val h = (dst.height() / k).toInt()
        val l = (b.width - w) / 2; val t = (b.height - h) / 2
        c.drawBitmap(b, Rect(l, t, l + w, t + h), dst, p)
    }

    /**
     * The darkening over the picture: the chosen darkness everywhere, and over it, by [top] (0..1),
     * a black shade through the date row that fades out below it.
     */
    fun drawShade(c: Canvas, width: Int, height: Int, topH: Float, dim: Float, p: Paint, top: Float = 1f) {
        val d = (dim * 255).toInt().coerceIn(0, 255)
        p.shader = null
        p.color = d shl 24
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        if (top <= 0.004f || topH <= 1f) return
        p.color = 0xFF000000.toInt() // full alpha, or it would fade the gradient too
        val end = topH * 1.8f
        fun a(x: Int) = ((x * top).toInt().coerceIn(0, 255)) shl 24
        p.shader = LinearGradient(0f, 0f, 0f, end, intArrayOf(a(0xF2), a(0xE0), 0), floatArrayOf(0f, topH / end, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), end, p)
        p.shader = null
    }

    /**
     * [b] covering [w] x [h], scrolled left by [shift] px and repeated endlessly, every other copy
     * mirrored so the joins are seamless. With [shift] 0 it's the same as [drawCover].
     */
    fun drawTiled(c: Canvas, b: Bitmap, w: Int, h: Int, shift: Float, p: Paint) {
        val k = max(w / b.width.toFloat(), h / b.height.toFloat())
        val tw = b.width * k
        val th = b.height * k
        val ty = (h - th) / 2f
        val start = -(tw - w) / 2f - shift
        var i = kotlin.math.floor(-start / tw).toInt()
        val dst = android.graphics.RectF()
        while (true) {
            val x = start + i * tw
            if (x >= w) break
            dst.set(x, ty, x + tw, ty + th)
            if (Math.floorMod(i, 2) == 1) {
                c.save(); c.scale(-1f, 1f, dst.centerX(), 0f); c.drawBitmap(b, null, dst, p); c.restore()
            } else c.drawBitmap(b, null, dst, p)
            i++
        }
    }
}

/** The wallpaper layer under home's panels. [blurAmount] 0 = Today (sharp), 1 = the other panels. */
class WallpaperView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private var stamp = -1L
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint()
    var blurAmount = 0f
        set(v) { if (abs(v - field) > 0.004f || v == 0f || v == 1f) { field = v; applyBlur() } }
    /** Off: hidden even with a picture set (the other layer is showing it). */
    var active = true
        set(v) { field = v; visibility = if (bmp != null && v) VISIBLE else GONE }

    /** Where Today's date row ends (the black gradient's length), device px. */
    var topH = 0f

    /**
     * For Today's own copy (inside the scaled panel, below the status bar): how far above this view
     * the screen starts, and the screen's height, in this view's px. Then it matches the full-screen one.
     */
    var screenTop = 0f
    var screenH = 0f

    /** How far the picture has scrolled, in screen widths (grows without end as you swipe round). */
    var phase = 0f
        set(v) { if (abs(v - field) > 0.0001f) { field = v; if (parallax) invalidate() } }
    private var parallax = true
    private var topShade = true

    /** How far Today is scrolled (device px): the dark top fades out as the date scrolls away. */
    var todayScroll = 0f
        set(v) { val c = v.coerceAtLeast(0f); if (abs(c - field) > 0.5f) { field = c; if (topShade) invalidate() } }
    private var lastRing = Float.NaN
    private var travelled = 0f

    /** Follow home's ring [offset] (of [n] views): its steps add up, even across its wrap-around. */
    fun ring(offset: Float, n: Int) {
        if (lastRing.isNaN()) lastRing = offset
        var d = offset - lastRing
        d = ((d + n / 2f) % n + n) % n - n / 2f
        lastRing = offset
        travelled += d
        phase = travelled * Wallpaper.PARALLAX
    }

    /** Pick up a new or removed picture and the latest settings. */
    fun refresh() {
        val s = if (Wallpaper.exists(context)) Wallpaper.stamp(context) else 0L
        if (s != stamp) { stamp = s; bmp = if (s == 0L) null else Wallpaper.load(context) }
        visibility = if (bmp != null && active) VISIBLE else GONE
        parallax = Wallpaper.parallax(context)
        topShade = Wallpaper.topShade(context)
        applyBlur(); invalidate()
    }

    private fun applyBlur() {
        if (Build.VERSION.SDK_INT < 31) return
        val r = Wallpaper.blur(context) * Wallpaper.MAX_BLUR * blurAmount
        setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        val h = if (screenH > 0f) screenH.toInt() else height
        c.save(); c.translate(0f, -screenTop)
        // With parallax the picture scrolls with the views, repeating (mirrored) without end.
        Wallpaper.drawTiled(c, b, width, h, if (parallax) phase * width else 0f, p)
        // Today: the dark top for the date and weather fades in as Today arrives (it doesn't slide).
        val t = 1f - blurAmount
        val scrolled = 1f - (todayScroll / topH.coerceAtLeast(1f)).coerceIn(0f, 1f)
        Wallpaper.drawShade(c, width, h, topH, Wallpaper.dim(context), shade, if (topShade) t * t * scrolled else 0f)
        c.restore()
    }

    companion object {
        /** How far the ring [offset] is from Today ([page] of [n]), 0..1. */
        fun distance(offset: Float, page: Int, n: Int): Float {
            var d = abs(offset - page) % n
            d = min(d, n - d)
            return d.coerceIn(0f, 1f)
        }
    }
}
