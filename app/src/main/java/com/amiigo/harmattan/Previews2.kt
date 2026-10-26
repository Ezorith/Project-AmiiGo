package com.amiigo.harmattan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import java.util.Date

/**
 * The Customize sheets' previews, pinned under the header: each shows the top of its panel at the
 * page's width, the lower half fading into the black page, and follows the sliders while they move.
 * All three are the same height, so changing a size never shifts the settings below.
 */
abstract class PanelPreview(ctx: Context) : View(ctx) {
    private val fade = Paint()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width * HEIGHT).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        drawPanel(c, width / 480f)
        c.restore()
        fade.shader = LinearGradient(0f, height * 0.5f, 0f, height.toFloat(), 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, height * 0.5f, width.toFloat(), height.toFloat(), fade)
    }

    /** Draw the panel; [u] is device px per N9 px at the page's width. */
    abstract fun drawPanel(c: Canvas, u: Float)

    companion object { const val HEIGHT = 0.82f }
}

/** Recents: the open apps' tiles as the switcher lays them out (large or three to a row). */
class RecentsPreview(ctx: Context) : PanelPreview(ctx) {
    var contentScale = Apps.switcherScale; set(v) { field = v; invalidate() }
    var largeMax = Apps.switcherLargeMax; set(v) { field = v; invalidate() }
    private val clip = Path()

    override fun drawPanel(c: Canvas, u: Float) {
        val apps = Apps.recentApps()
        val large = apps.size <= largeMax
        val cols = if (large) 2 else 3
        val tw = if (large) 216f else 138f
        val th = if (large) 368f else 235f
        val sp = if (large) 20f else 19f
        for ((i, e) in apps.withIndex()) {
            val col = i % cols; val row = i / cols
            val x = (14f + col * (tw + sp)) * u
            val y = (24f + row * (th + sp)) * u
            if (y > height) break
            val r = RectF(x, y, x + tw * u, y + th * u)
            clip.reset(); clip.addRoundRect(r, 6 * u, 6 * u, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            val snap = Previews.get(e)
            if (snap != null) Previews.draw(c, r, snap) else WindowArt.drawCard(c, r, e, 255, contentScale)
            c.restore()
        }
    }
}

/** Today: the status bar (N9 or Android's), the date and weather, and the first notifications. */
class TodayPreview(ctx: Context) : PanelPreview(ctx) {
    var scale = Apps.todayScale; set(v) { field = v; invalidate() }
    var statusScale = Apps.statusBarScale; set(v) { field = v; invalidate() }
    var n9StatusBar = !Apps.systemStatusBar; set(v) { field = v; invalidate() }
    var showOperator = Apps.showOperator; set(v) { field = v; invalidate() }
    var dim = Wallpaper.dim(ctx); set(v) { field = v; invalidate() }
    private val wall = Wallpaper.load(ctx)
    private val shade = Paint()

    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val line = Paint().apply { color = 0x29FFFFFF }
    private val pill = Pill(Pill.Kind.NORMAL)

    private fun text(c: Canvas, s: String, x: Float, base: Float, size: Float, face: android.graphics.Typeface, color: Int, maxW: Float = 1e6f) {
        p.typeface = face; p.textSize = size; p.color = color
        c.drawText(TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString(), x, base, p)
    }

    override fun drawPanel(c: Canvas, u0: Float) {
        // The wallpaper, as home shows it behind Today (the preview is the screen's top part).
        wall?.let {
            val screenH = (width * resources.displayMetrics.heightPixels / resources.displayMetrics.widthPixels.toFloat()).toInt()
            Wallpaper.drawCover(c, it, android.graphics.Rect(0, 0, width, screenH), bmp)
            Wallpaper.drawShade(c, width, screenH, (36f + 110f * scale) * u0, dim, shade)
        }
        // Status bar.
        var top: Float
        val clock = android.text.format.DateFormat.getTimeFormat(context).format(Date()).replace(Regex("\\s?[AaPp][Mm]$"), "")
        if (n9StatusBar) {
            val k = u0 * statusScale
            val h = 36f * k
            val iconH = 15f * k
            val cy = h / 2f
            var x = 8f * k
            fun icon(res: Int, cropL: Int = 0) {
                val b = N9.bitmap(context, res)
                val w = iconH * (b.width - cropL) / b.height
                c.drawBitmap(b, android.graphics.Rect(cropL, 0, b.width, b.height), RectF(x, cy - iconH / 2f, x + w, cy + iconH / 2f), bmp)
                x += w + 6f * k
            }
            icon(R.drawable.n9_status_battery8)
            icon(R.drawable.n9_status_network5, 4 * N9.HD)
            if (showOperator) {
                val op = runCatching { context.getSystemService(android.telephony.TelephonyManager::class.java)?.networkOperatorName }.getOrNull().orEmpty().ifEmpty { "Operator" }
                p.textSize = N9.FONT_XSMALL * k
                text(c, op, x, cy - (p.ascent() + p.descent()) / 2f, N9.FONT_XSMALL * k, N9.regular, Color.WHITE)
                x += p.measureText(op) + 8f * k
            }
            icon(R.drawable.n9_status_wlan)
            p.textSize = N9.FONT_XSMALL * k; p.typeface = N9.regular
            text(c, clock, width - 8f * k - p.measureText(clock), cy - (p.ascent() + p.descent()) / 2f, N9.FONT_XSMALL * k, N9.regular, Color.WHITE)
            top = h
        } else {
            // Android's own bar, as One UI draws it: the clock on the left, signal, Wi-Fi and battery on the right.
            val h = 40f * u0
            val cy = h / 2f
            p.textSize = 18f * u0
            text(c, clock, 18f * u0, cy - (p.ascent() + p.descent()) / 2f, 18f * u0, N9.bold, Color.WHITE)
            val iconH = 13f * u0
            var x = width - 18f * u0
            for ((res, crop) in listOf(R.drawable.n9_status_battery8 to 0, R.drawable.n9_status_wlan to 0, R.drawable.n9_status_network5 to 4 * N9.HD)) {
                val b = N9.bitmap(context, res)
                val w = iconH * (b.width - crop) / b.height
                x -= w
                c.drawBitmap(b, android.graphics.Rect(crop, 0, b.width, b.height), RectF(x, cy - iconH / 2f, x + w, cy + iconH / 2f), bmp)
                x -= 6f * u0
            }
            top = h
        }
        // Today, at its size.
        val u = u0 * scale
        val t = N9.textScale
        top += 2f * u
        val now = Date()
        text(c, android.text.format.DateFormat.format("EEEE", now).toString(), 2f * u + 14f * u0, top + 40f * u * t, 40f * u * t, N9.light, Color.WHITE)
        text(c, android.text.format.DateFormat.format("d MMMM yyyy", now).toString(), 2f * u + 14f * u0, top + 80f * u * t, 32f * u * t, N9.light, 0xFF808080.toInt())
        if (Weather.hasData) {
            val r = RectF(width - 14f * u0 - 120f * u, top + 4f * u, width - 14f * u0, top + 91f * u)
            p.textSize = 16f * u
            text(c, Weather.city, r.centerX() - p.apply { typeface = N9.bold }.measureText(Weather.city).coerceAtMost(r.width()) / 2f, r.top + 19f * u, 16f * u, N9.bold, Color.WHITE, r.width())
            c.drawRect(r.left + 4f * u, r.top + 28f * u, r.right - 4f * u, r.top + 29f * u, line)
            text(c, "${Weather.temperature}°", r.left + 4f * u, r.bottom - 16f * u, 32f * u, N9.regular, Color.WHITE)
            Weather.icon()?.let { c.drawBitmap(N9.bitmap(context, it), null, RectF(r.right - 52f * u, r.bottom - 52f * u, r.right - 4f * u, r.bottom - 4f * u), bmp) }
        }
        top += 96f * u
        val left = 14f * u0; val right = width - 14f * u0
        c.drawRect(left, top, right, top + maxOf(1f, u), line)
        top += 12f * u
        if (EventStore.notifications.none { it.clearable }) return
        // Notifications heading and Clear.
        text(c, context.getString(R.string.qtn_noti_title), left + 2f * u, top + 26f * u, 24f * u * t, N9.bold, 0xFF646464.toInt())
        val pr = RectF(right - 160f * u, top, right, top + 31f * u)
        pill.setBounds(pr.left.toInt(), pr.top.toInt(), pr.right.toInt(), pr.bottom.toInt()); pill.draw(c)
        val clr = context.getString(R.string.qtn_noti_clear)
        p.textSize = 20f * u * t; p.typeface = N9.bold
        text(c, clr, pr.centerX() - p.measureText(clr) / 2f, pr.centerY() - (p.ascent() + p.descent()) / 2f, 20f * u * t, N9.bold, Color.WHITE)
        top += 52f * u
        for (n in EventStore.notifications.take(3)) {
            n.icon?.let { c.drawBitmap(it, null, RectF(left + 2f * u, top + 10f * u, left + 66f * u, top + 74f * u), bmp) }
            val tx = left + 82f * u
            text(c, n.title, tx, top + 30f * u * t, 22f * u * t, N9.regular, Color.WHITE, right - tx)
            n.subtitle?.let { text(c, it, tx, top + 54f * u * t, 16f * u * t, N9.regular, 0xFFC8C8C8.toInt(), right - tx) }
            top += 100f * u
            if (top > height) break
        }
    }
}
