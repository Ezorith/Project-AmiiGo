package com.amiigo.harmattan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.min

/**
 * Harmattan ("blanco" theme) design constants, recovered from the N9 firmware
 * (DFL61_HARMATTAN 40.2012.21-3): /usr/share/themes/{base,blanco}/meegotouch.
 *
 * Every size here is in N9 pixels. The N9 is 480x854, and MeeGo Touch stylesheets
 * treat 1 mm as 10 px (e.g. HEIGHT_STATUSBAR = 36px is referred to as "3.6mm"), so
 * a stylesheet value of "2.4mm" is 24 N9 px. [px] maps them onto the device so the
 * 480-px-wide N9 layout fills the phone's width exactly.
 */
object N9 {
    /** Device pixels per N9 pixel. */
    var s = 1f
        private set
    /** Screen width in N9 pixels: the N9's 480px layout always spans the screen. */
    var widthN9 = 480f
        private set

    lateinit var light: Typeface
    lateinit var regular: Typeface
    lateinit var bold: Typeface
    /** "Nokia Pure Text T" — tabular figures, used for clocks. */
    lateinit var tabular: Typeface
    private var ready = false

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        // The N9's 480px layout exactly fills the screen width. Home settings scale elements on their
        // own (spacing, icons, text), never the whole interface.
        s = min(dm.widthPixels, dm.heightPixels) / 480f
        widthN9 = min(dm.widthPixels, dm.heightPixels) / s
        // Text size (Home settings): panel text.
        textScale = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getFloat("text_scale", 1f).coerceIn(0.7f, 1.5f)
        if (ready) return
        val r = ctx.resources
        light = r.getFont(R.font.nokia_pure_light)
        regular = r.getFont(R.font.nokia_pure_regular)
        bold = r.getFont(R.font.nokia_pure_bold)
        tabular = r.getFont(R.font.nokia_pure_t_regular)
        ready = true
    }

    fun px(v: Float) = v * s
    fun px(v: Int) = v * s
    fun ipx(v: Float) = (v * s + 0.5f).toInt()
    fun ipx(v: Int) = (v * s + 0.5f).toInt()

    // ---- constants.ini [Fonts] — sizes are pixel heights on the N9 ----
    const val FONT_XLARGE = 32f
    const val FONT_LARGE = 28f
    const val FONT_DEFAULT = 24f
    const val FONT_SMALL = 20f
    const val FONT_XSMALL = 18f
    const val FONT_XXSMALL = 16f

    // ---- blanco palette (constants.ini [Palette]) ----
    const val COLOR_INVERTED_FOREGROUND = 0xFFFFFFFF.toInt()
    const val COLOR_INVERTED_SECONDARY_FOREGROUND = 0xFFC8C8C8.toInt()
    const val COLOR_INVERTED_BACKGROUND = 0xFF000000.toInt()
    const val COLOR_ACCENT1 = 0xFF4591FF.toInt()

    /** The theme bitmaps are stored at this multiple of their N9 size (tools/hd_assets.py). */
    const val HD = 3

    /** Status bar height: HEIGHT_STATUSBAR = 36px. */
    const val STATUSBAR = 36f

    /** Text size multiplier from Home settings (1 = the N9's sizes). */
    var textScale = 1f
        private set

    /** Panel text: N9 size × the user's text size. [scaled] false keeps the N9 size (status bar, settings). */
    fun textPaint(face: Typeface, sizeN9: Float, color: Int, scaled: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = face
        textSize = px(sizeN9) * (if (scaled) textScale else 1f)
        this.color = color
    }

    private val bitmapCache = HashMap<Int, Bitmap>()

    /** Theme graphics are kept in drawable-nodpi at their original N9 pixel size. */
    fun bitmap(ctx: Context, res: Int): Bitmap = synchronized(bitmapCache) {
        bitmapCache.getOrPut(res) { BitmapFactory.decodeResource(ctx.resources, res, BitmapFactory.Options().apply { inScaled = false }) }
    }
}
