package com.amiigo.harmattan

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The N9's Search (/usr/bin/search), opened by pulling down at the top of a view: a light sheet
 * (COLOR_BACKGROUND #E0E1E2, rounded corners, as its splash screen) with the search field in a
 * header strip (meegotouch-search-input-field-background) and results as a list, each an icon with
 * its name and kind ("Application"). Field: MTextEditStyle, FONT_FAMILY_LIGHT 24px, prompt #A6A8AB.
 */
@SuppressLint("ViewConstructor")
class SearchOverlay(private val home: HomeActivity) : FrameLayout(home) {
    companion object {
        const val HEADER_H = 72f
        const val ROW_H = 88f
        const val ICON = 64f
        const val MAX_RESULTS = 60
        private const val FG = 0xFF282828.toInt()            // COLOR_FOREGROUND
        private const val FG2 = 0xFF505050.toInt()           // COLOR_SECONDARY_FOREGROUND
        private const val BG = 0xFFE0E1E2.toInt()            // COLOR_BACKGROUND
    }

    private val sheet = LinearLayout(home).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(BG); cornerRadius = N9.px(12f) }
        clipToOutline = true
    }
    private val field = EditText(home)
    private val list = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(home).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    private var anim: android.animation.ValueAnimator? = null
    var isOpen = false
        private set
    /** Where the sheet starts (under the status bar), set by home. */
    var top0 = 0
        set(v) { field = v; (sheet.layoutParams as LayoutParams).topMargin = v; requestLayout() }

    init {
        visibility = GONE
        setBackgroundColor(0xFF000000.toInt())
        isClickable = true
        val header = FrameLayout(home).apply {
            background = NineSlice(N9.bitmap(home, R.drawable.n9_search_header), 4, 4, 4, 4)
        }
        field.apply {
            background = NineSlice(N9.bitmap(home, R.drawable.n9_textedit_selected), 10, 10, 10, 10)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, N9.px(24f))
            typeface = N9.light
            setTextColor(FG)
            hint = home.getString(R.string.search)
            setHintTextColor(0xFFA6A8AB.toInt())
            highlightColor = 0xFFA1BFFF.toInt()   // COLOR_SELECT
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(N9.ipx(14), 0, N9.ipx(52), 0)
            gravity = Gravity.CENTER_VERTICAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { results() }
            })
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { (list.getChildAt(0) as? Row)?.performTap(); true } else false
            }
        }
        header.addView(field, LayoutParams(-1, N9.ipx(52), Gravity.CENTER_VERTICAL).apply { leftMargin = N9.ipx(12); rightMargin = N9.ipx(12) })
        val glass = ImageView(home).apply { setImageBitmap(N9.bitmap(home, R.drawable.n9_icon_search)); scaleType = ImageView.ScaleType.FIT_CENTER }
        header.addView(glass, LayoutParams(N9.ipx(32), N9.ipx(32), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = N9.ipx(24) })
        sheet.addView(header, LinearLayout.LayoutParams(-1, N9.ipx(HEADER_H)))
        scroll.addView(list, LayoutParams(-1, -2))
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(sheet, LayoutParams(-1, -1))
        // The keyboard (and gesture bar) take the bottom; the list ends above them.
        setOnApplyWindowInsetsListener { _, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30)
                maxOf(insets.getInsets(WindowInsets.Type.ime()).bottom, insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            (sheet.layoutParams as LayoutParams).bottomMargin = bottom
            sheet.requestLayout()
            insets
        }
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        field.setText("")
        results()
        visibility = VISIBLE
        animate(true) {}
        field.requestFocus()
        post { home.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0) }
    }

    fun close(animated: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        home.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
        if (!animated) { anim?.cancel(); visibility = GONE; return }
        animate(false) { visibility = GONE }
    }

    /** Comes down from the top and fades in (250ms outexpo); goes back up and out. */
    private fun animate(show: Boolean, end: () -> Unit) {
        anim?.cancel()
        val from = alpha.takeIf { visibility == VISIBLE && it < 1f } ?: if (show) 0f else 1f
        anim = android.animation.ValueAnimator.ofFloat(from, if (show) 1f else 0f).apply {
            duration = if (show) 250 else 200
            interpolator = Ease.outExpo
            addUpdateListener {
                val v = it.animatedValue as Float
                alpha = v
                sheet.translationY = -N9.px(80f) * (1f - v)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { end() }
            })
            start()
        }
    }

    /** Apps whose names match (starting with the query first, then a word in them, then anywhere), and the web. */
    private fun results() {
        list.removeAllViews()
        val q = field.text.toString().trim()
        val apps = Apps.all.distinctBy { it.key }.filter { !Apps.isSearch(it) }
        val shown = if (q.isEmpty()) apps.sortedBy { it.label.lowercase() } else {
            val lq = q.lowercase()
            apps.mapNotNull { e ->
                val l = e.label.lowercase()
                when {
                    l.startsWith(lq) -> 0 to e
                    l.split(' ', '-', '.').any { it.startsWith(lq) } -> 1 to e
                    l.contains(lq) -> 2 to e
                    else -> null
                }
            }.sortedWith(compareBy({ it.first }, { it.second.label.lowercase() })).map { it.second }
        }
        for (e in shown.take(MAX_RESULTS)) list.addView(Row(home, e.icon, e.label, home.getString(R.string.search_kind_app)) {
            close(false)
            Apps.launch(e, null, null)
        }, LinearLayout.LayoutParams(-1, N9.ipx(ROW_H)))
        if (q.isNotEmpty()) list.addView(Row(home, N9.bitmap(home, R.drawable.n9_app_browser),
            home.getString(R.string.search_web, q), home.getString(R.string.search_kind_web)) {
            close(false)
            searchWeb(q)
        }, LinearLayout.LayoutParams(-1, N9.ipx(ROW_H)))
        scroll.scrollTo(0, 0)
    }

    private fun searchWeb(q: String) {
        val ok = runCatching {
            home.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
        if (!ok) runCatching {
            home.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && isOpen) { close(); return true }
        return super.dispatchKeyEvent(event)
    }

    /** One result: icon, name (FONT_DEFAULT, COLOR_FOREGROUND) and kind (FONT_SMALL, secondary). */
    private class Row(ctx: Context, private val icon: Bitmap, private val title: String, private val kind: String,
                      private val onTap: () -> Unit) : View(ctx) {
        private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val titlePaint = N9.textPaint(N9.regular, 26f, FG)
        private val kindPaint = N9.textPaint(N9.regular, 20f, FG2)
        private val pressPaint = Paint().apply { color = 0x1F000000 }
        private var pressed0 = false
            set(v) { field = v; invalidate() }

        fun performTap() = onTap()

        override fun onDraw(c: Canvas) {
            if (pressed0) c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pressPaint)
            val s = N9.px(ICON)
            val x = N9.px(16f)
            val y = (height - s) / 2f
            c.drawBitmap(icon, null, RectF(x, y, x + s, y + s), iconPaint)
            val tx = x + s + N9.px(16f)
            val avail = width - tx - N9.px(16f)
            val tfm = titlePaint.fontMetrics; val kfm = kindPaint.fontMetrics
            val th = tfm.descent - tfm.ascent; val kh = kfm.descent - kfm.ascent
            val top = (height - th - kh) / 2f
            c.drawText(TextUtils.ellipsize(title, android.text.TextPaint(titlePaint), avail, TextUtils.TruncateAt.END).toString(), tx, top - tfm.ascent, titlePaint)
            c.drawText(kind, tx, top + th - kfm.ascent, kindPaint)
        }

        private var downY = 0f
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.y; pressed0 = true }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(e.y - downY) > N9.px(20f)) pressed0 = false
                MotionEvent.ACTION_UP -> { if (pressed0) { pressed0 = false; onTap() } }
                MotionEvent.ACTION_CANCEL -> pressed0 = false
            }
            return true
        }
    }
}
