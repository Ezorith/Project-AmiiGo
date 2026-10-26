package com.amiigo.harmattan

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The meegotouch-home button (Clear, Refresh, New folder…), drawn at the screen's resolution instead of
 * stretching the 30×31 theme bitmap in nine pieces (which showed faint seams and soft edges). Shape and
 * shading are measured from that bitmap: a 7px-radius body with a dark vertical gradient, a 1px light
 * inner top edge and a faint light rim above; pressed is the blue gradient with a light rim below;
 * disabled is the normal button at 40%.
 */
class Pill(private val kind: Kind) : Drawable() {
    enum class Kind { NORMAL, PRESSED, DISABLED }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private val o = RectF()
    private var alpha0 = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val u = N9.px(1f) // one N9 pixel
        val a = alpha0 * (if (kind == Kind.DISABLED) 0.4f else 1f) / 255f
        // Body inset as in the bitmap: 1px at the sides and bottom, 2px at the top.
        r.set(b.left + u, b.top + 2 * u, b.right - u, b.bottom - u)
        val rad = minOf(N9.px(7.5f), r.height() / 2f)
        o.set(r.left - u, r.top - u, r.right + u, r.bottom + u)
        when (kind) {
            Kind.NORMAL, Kind.DISABLED -> {
                // Light rim above, fading out down the sides.
                rim.shader = LinearGradient(0f, o.top, 0f, o.bottom,
                    intArrayOf(c(0x50FFFFFF, a), c(0x18FFFFFF, a), c(0x0AFFFFFF, a)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(o, rad + u, rad + u, rim)
                body.shader = LinearGradient(0f, r.top, 0f, r.bottom, c(0xFF2C2C2C.toInt(), a), c(0xFF171717.toInt(), a), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(r, rad, rad, body)
                // 1px lighter inner top edge.
                edge.strokeWidth = u
                edge.shader = LinearGradient(0f, r.top, 0f, r.top + rad, c(0xFF3B3B3B.toInt(), a), c(0x002C2C2C, a), Shader.TileMode.CLAMP)
                val e = RectF(r.left + u / 2, r.top + u / 2, r.right - u / 2, r.bottom - u / 2)
                canvas.drawRoundRect(e, rad - u / 2, rad - u / 2, edge)
            }
            Kind.PRESSED -> {
                // Light rim below (the button pressed in), faint dark above.
                rim.shader = LinearGradient(0f, o.top, 0f, o.bottom,
                    intArrayOf(c(0x14000000, a), c(0x00000000, a), c(0x6EBFBFBF, a)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(o, rad + u, rad + u, rim)
                body.shader = LinearGradient(0f, r.top, 0f, r.bottom,
                    intArrayOf(c(0xFF234765.toInt(), a), c(0xFF1C5586.toInt(), a), c(0xFF0D4F85.toInt(), a), c(0xFF0A4D84.toInt(), a), c(0xFF083F6C.toInt(), a)),
                    floatArrayOf(0f, 0.12f, 0.5f, 0.85f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(r, rad, rad, body)
                // Darker sides: the inner shadow of a pressed button.
                edge.shader = null
                for ((w, al) in listOf(1f to 0.55f, 2.5f to 0.25f)) {
                    edge.strokeWidth = u * w
                    edge.color = c(0xFF0A3156.toInt(), a * al)
                    val e = RectF(r.left + u * w / 2, r.top + u * w / 2, r.right - u * w / 2, r.bottom - u * w / 2)
                    canvas.drawRoundRect(e, rad - u * w / 2, rad - u * w / 2, edge)
                }
            }
        }
    }

    /** [argb] with its alpha multiplied by [k]. */
    private fun c(argb: Int, k: Float): Int {
        val al = ((argb ushr 24) * k).toInt().coerceIn(0, 255)
        return (al shl 24) or (argb and 0xFFFFFF)
    }

    override fun setAlpha(alpha: Int) { alpha0 = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
