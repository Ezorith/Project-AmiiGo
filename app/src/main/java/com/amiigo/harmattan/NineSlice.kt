package com.amiigo.harmattan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * MScalableImage equivalent: a theme bitmap with fixed borders that stretches in the middle.
 * Border order follows the MeeGo CSS: `background-image: "name" left right top bottom`.
 * Borders are given in N9 pixels and drawn scaled by [N9.s], like everything else.
 */
class NineSlice(
    private val bmp: Bitmap,
    private val l: Int, private val r: Int, private val t: Int, private val b: Int,
) : Drawable() {
    // No anti-aliasing: with it, each piece's edges blend with what's behind and show as faint seams.
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()

    override fun draw(canvas: Canvas) {
        val bd = bounds
        if (bd.isEmpty) return
        val w = bmp.width; val h = bmp.height
        var dl = N9.px(l); var dr = N9.px(r); var dt = N9.px(t); var db = N9.px(b)
        // Shrink borders proportionally if the target is smaller than the borders.
        if (dl + dr > bd.width()) { val k = bd.width() / (dl + dr); dl *= k; dr *= k }
        if (dt + db > bd.height()) { val k = bd.height() / (dt + db); dt *= k; db *= k }
        // Borders are N9 px; the bitmaps are stored at N9.HD times that.
        val k = N9.HD
        val xs = intArrayOf(0, l * k, w - r * k, w)
        val ys = intArrayOf(0, t * k, h - b * k, h)
        // Whole device pixels, so neighbouring pieces meet exactly.
        val xd = floatArrayOf(bd.left.toFloat(), Math.round(bd.left + dl).toFloat(), Math.round(bd.right - dr).toFloat(), bd.right.toFloat())
        val yd = floatArrayOf(bd.top.toFloat(), Math.round(bd.top + dt).toFloat(), Math.round(bd.bottom - db).toFloat(), bd.bottom.toFloat())
        for (j in 0..2) for (i in 0..2) {
            if (xs[i + 1] <= xs[i] || ys[j + 1] <= ys[j]) continue
            src.set(xs[i], ys[j], xs[i + 1], ys[j + 1])
            dst.set(xd[i], yd[j], xd[i + 1], yd[j + 1])
            canvas.drawBitmap(bmp, src, dst, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
