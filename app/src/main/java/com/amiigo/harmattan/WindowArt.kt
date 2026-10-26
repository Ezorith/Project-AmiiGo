package com.amiigo.harmattan

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils

/**
 * An app drawn as a window card — what switcher tiles show, since Android doesn't let launchers show
 * other apps' windows (on the N9 each tile was the live window).
 */
object WindowArt {
    private val cardBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripPaint = Paint().apply { color = 0x33000000 }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    private fun shade(c: Int, k: Float) = Color.rgb(
        (Color.red(c) * k).toInt().coerceIn(0, 255), (Color.green(c) * k).toInt().coerceIn(0, 255), (Color.blue(c) * k).toInt().coerceIn(0, 255))

    /**
     * A gradient in the app's colour (SwipeSwitcherButtonStyle brightness 1.08), a title strip like the
     * toolbar every Harmattan window had, and the app icon and name in the middle.
     */
    fun drawCard(canvas: Canvas, r: RectF, e: AppEntry, alpha: Int = 255, contentScale: Float = Apps.switcherScale) {
        // 1 = large switcher tile, times the Open apps size (Home settings) for what's on the card.
        val k = r.width() / N9.px(216f) * contentScale
        cardBg.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            shade(e.tint, 0.55f * 1.08f), shade(e.tint, 0.16f * 1.08f), Shader.TileMode.CLAMP)
        cardBg.alpha = alpha
        canvas.drawRect(r, cardBg)

        val strip = N9.px(52f) * k
        stripPaint.alpha = (0x33 * alpha / 255)
        canvas.drawRect(r.left, r.top, r.right, r.top + strip, stripPaint)
        iconPaint.alpha = alpha
        val si = strip * 0.6f
        val sx = r.left + N9.px(10f) * k
        canvas.drawBitmap(e.icon, null, RectF(sx, r.top + (strip - si) / 2f, sx + si, r.top + (strip + si) / 2f), iconPaint)
        titlePaint.typeface = N9.bold
        titlePaint.color = Color.WHITE
        titlePaint.alpha = alpha
        titlePaint.textSize = N9.px(N9.FONT_XSMALL) * k.coerceAtLeast(0.75f) * N9.textScale
        val tx = sx + si + N9.px(6f) * k
        val title = TextUtils.ellipsize(e.label, titlePaint, r.right - tx - N9.px(8f), TextUtils.TruncateAt.END).toString()
        val tfm = titlePaint.fontMetrics
        canvas.drawText(title, tx, r.top + strip / 2f - (tfm.ascent + tfm.descent) / 2f, titlePaint)

        val icon = N9.px(80f) * k
        val ix = r.centerX() - icon / 2f
        val iy = r.top + strip + (r.height() - strip - icon) / 2f - N9.px(12f) * k
        canvas.drawBitmap(e.icon, null, RectF(ix, iy, ix + icon, iy + icon), iconPaint)
        labelPaint.typeface = N9.regular
        labelPaint.color = Color.WHITE
        labelPaint.alpha = alpha
        labelPaint.textSize = N9.px(N9.FONT_SMALL) * k.coerceAtLeast(0.75f) * N9.textScale
        val label = TextUtils.ellipsize(e.label, labelPaint, r.width() - N9.px(16f), TextUtils.TruncateAt.END).toString()
        canvas.drawText(label, r.centerX() - labelPaint.measureText(label) / 2f, iy + icon + N9.px(10f) * k - labelPaint.fontMetrics.ascent, labelPaint)
    }
}
