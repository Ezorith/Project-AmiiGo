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
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    const val MAX_BLUR = 90f // device px

    /** Copy the chosen picture in, scaled to the screen. Blocking. */
    fun save(ctx: Context, uri: Uri): Boolean = runCatching {
        val dm = ctx.resources.displayMetrics
        val target = max(dm.widthPixels, dm.heightPixels)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
        val b = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        file(ctx).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        true
    }.getOrDefault(false)

    fun remove(ctx: Context) { file(ctx).delete() }

    fun load(ctx: Context): Bitmap? = file(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** [b] drawn to cover [dst], centred. */
    fun drawCover(c: Canvas, b: Bitmap, dst: Rect, p: Paint) {
        val k = max(dst.width() / b.width.toFloat(), dst.height() / b.height.toFloat())
        val w = (dst.width() / k).toInt(); val h = (dst.height() / k).toInt()
        val l = (b.width - w) / 2; val t = (b.height - h) / 2
        c.drawBitmap(b, Rect(l, t, l + w, t + h), dst, p)
    }

    /** The darkening over the picture: black at the top fading to [dim] below the date row. */
    fun drawShade(c: Canvas, width: Int, height: Int, topH: Float, dim: Float, p: Paint) {
        val d = (dim * 255).toInt().coerceIn(0, 255)
        p.color = 0xFF000000.toInt() // full alpha, or it would fade the gradient too
        // Near-black through the date row, then a long fade to the chosen darkness.
        val end = topH * 1.8f
        p.shader = LinearGradient(0f, 0f, 0f, end, intArrayOf(0xF2000000.toInt(), 0xE0000000.toInt(), (d shl 24)),
            floatArrayOf(0f, topH / end, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), end, p)
        p.shader = null
        p.color = d shl 24
        c.drawRect(0f, end, width.toFloat(), height.toFloat(), p)
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

    /** Pick up a new or removed picture and the latest settings. */
    fun refresh() {
        val s = if (Wallpaper.exists(context)) Wallpaper.stamp(context) else 0L
        if (s != stamp) { stamp = s; bmp = if (s == 0L) null else Wallpaper.load(context) }
        visibility = if (bmp != null && active) VISIBLE else GONE
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
        Wallpaper.drawCover(c, b, Rect(0, 0, width, height), p)
        // Today: dark at the top for the date and weather, then the chosen darkness; elsewhere the darkness only.
        val top = topH * (1f - blurAmount)
        Wallpaper.drawShade(c, width, height, max(1f, top), Wallpaper.dim(context), shade)
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
