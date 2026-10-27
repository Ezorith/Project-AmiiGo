package com.amiigo.harmattan

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Home settings: permissions, appearance, apps, the switcher, feed sources. Harmattan-styled. */
class SettingsActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    /** The sheet's fixed top: the header strip and the panel's preview. */
    private var pinned: LinearLayout? = null
    private var appsPreview: HomePreview? = null
    private var recentsPreview: RecentsPreview? = null
    private var todayPreview: TodayPreview? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        N9.init(this)
        Apps.init(this)
        EventStore.init(this)
        Weather.init(this)
        scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK); isFillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(N9.ipx(16), N9.ipx(24), N9.ipx(16), N9.ipx(24)) }
        scroll.addView(list)
        if (panel == null) {
            setContentView(scroll)
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets
            }
        } else {
            // The Customize sheet: Done floats at the bottom over a dark gradient, as Back to top does.
            val frame = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            pinned = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(N9.ipx(16), 0, N9.ipx(16), 0)
                clipToPadding = false; clipChildren = false
            }
            column.addView(pinned, LinearLayout.LayoutParams(-1, -2))
            column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            frame.addView(column, android.widget.FrameLayout.LayoutParams(-1, -1))
            val done = BackToTopButton(this, getString(R.string.done)) { closeSheet() }
            frame.addView(done, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            done.show(true)
            setContentView(frame)
            frame.setOnApplyWindowInsetsListener { _, insets ->
                @Suppress("DEPRECATION")
                column.setPadding(0, insets.systemWindowInsetTop, 0, 0)
                @Suppress("DEPRECATION")
                done.bottomInset = insets.systemWindowInsetBottom
                insets
            }
        }
        SystemRecents.onPermissionResult { runOnUiThread { build(); Apps.refreshSystemRecents() } }
    }

    // Source names and icons arrive when the feed has been read; show them when they do.
    private var resumed = false
    private val feedChanged: () -> Unit = { if (resumed && !EventStore.refreshing) build() }

    override fun onResume() {
        super.onResume()
        resumed = true
        EventStore.addListener(feedChanged)
        build()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        EventStore.removeListener(feedChanged)
    }

    // ---- building blocks ----

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, light: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, N9.px(size))
        typeface = if (bold) N9.bold else if (light) N9.light else N9.regular
    }

    // N9 Settings (duicontrolpanel) look: CommonHeaderInverted page title, CommonGroupHeaderInverted
    // headers (grey bold 18, line to their left), list items of SIZE_LIST 8mm with CommonTitleInverted
    // (bold 26 white) and CommonSubTitleInverted (light 22 #C8C8C8), the 6.4mm settings icons, the
    // drill-down arrow, the meegotouch switch, and the 10% white pressed highlight.
    private val indent get() = N9.ipx(16)

    private fun pressedBg() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(0x1AFFFFFF))
        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    /** Rows span the full width (the list itself is indented). */
    private fun fullWidth(h: Int = -2) = LinearLayout.LayoutParams(-1, h).apply { leftMargin = -indent; rightMargin = -indent }

    private fun header(s: String) {
        list.addView(text(s, 32f, Color.WHITE, light = true).apply { setPadding(0, N9.ipx(8), 0, N9.ipx(8)) })
        list.addView(divider(), fullWidth(N9.ipx(2)))
    }

    private fun divider() = View(this).apply {
        background = android.graphics.drawable.BitmapDrawable(resources, N9.bitmap(this@SettingsActivity, R.drawable.n9_groupheader)).apply {
            setTileModeX(android.graphics.Shader.TileMode.REPEAT)
        }
    }

    /** A separator; [labelled] shows its name (only "System" does). */
    private fun group(s: String, labelled: Boolean = false) {
        lastRow = null
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = N9.ipx(40)
            setPadding(0, N9.ipx(10), 0, 0)
        }
        row.addView(divider(), LinearLayout.LayoutParams(0, N9.ipx(2), 1f))
        if (labelled) row.addView(text(s, 18f, 0xFF707070.toInt(), bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = N9.ipx(12) })
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = N9.ipx(6) })
    }

    /** The last row's title and where an "i" goes in it, for [note]. */
    private var lastRow: LinearLayout? = null
    private var lastTitle = ""
    private var lastInfoAt = 0

    private fun remember(row: LinearLayout, title: String, infoAt: Int) { lastRow = row; lastTitle = title; lastInfoAt = infoAt }

    /** A description: an "i" on the row above (as N9 Settings did), opening the text. */
    private fun note(s: String) {
        val row = lastRow
        if (row == null) {
            list.addView(text(s, 20f, 0xFF8C8C8C.toInt(), light = true).apply { setPadding(0, N9.ipx(4), 0, N9.ipx(12)) })
            return
        }
        lastRow = null
        val title = lastTitle
        row.addView(infoButton(title, s), lastInfoAt.coerceAtMost(row.childCount), LinearLayout.LayoutParams(N9.ipx(44), N9.ipx(44)).apply { marginStart = N9.ipx(4) })
    }

    private fun infoButton(title: String, s: String) = android.widget.ImageView(this).apply {
        setImageBitmap(N9.bitmap(this@SettingsActivity, R.drawable.n9_info))
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(N9.ipx(6), N9.ipx(6), N9.ipx(6), N9.ipx(6))
        background = pressedBg()
        setOnClickListener { dialog().setTitle(title).setMessage(s).setPositiveButton(android.R.string.ok, null).show() }
    }

    /** A list item: optional settings icon, title and subtitle, and the drill-down arrow when it opens something. */
    private fun item(title: String, value: String?, icon: Int = 0, drill: Boolean = true, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = N9.ipx(80)
            setPadding(indent, N9.ipx(8), indent, N9.ipx(8))
            background = pressedBg()
            setOnClickListener { onClick() }
        }
        if (icon != 0) row.addView(android.widget.ImageView(this).apply { setImageBitmap(N9.bitmap(this@SettingsActivity, icon)) },
            LinearLayout.LayoutParams(N9.ipx(64), N9.ipx(64)).apply { marginEnd = N9.ipx(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 26f, Color.WHITE, bold = true))
        val long = value != null && value.length > 42
        if (value != null && !long) texts.addView(text(value, 22f, N9.COLOR_INVERTED_SECONDARY_FOREGROUND, light = true))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        remember(row, title, row.childCount)
        if (long) note(value!!)
        if (drill) row.addView(android.widget.ImageView(this).apply { setImageBitmap(N9.bitmap(this@SettingsActivity, R.drawable.n9_drilldown)) },
            LinearLayout.LayoutParams(N9.ipx(32), N9.ipx(32)).apply { marginStart = N9.ipx(8) })
        list.addView(row, fullWidth())
    }

    /** A list item with the N9 switch on its right. */
    private fun switchItem(title: String, value: String?, on: Boolean, toggle: (Boolean) -> Unit) {
        val sw = N9Switch(this, on) { v -> toggle(v); build() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = N9.ipx(80)
            setPadding(indent, N9.ipx(8), indent, N9.ipx(8))
            background = pressedBg()
            setOnClickListener { sw.flip() }
        }
        row.addView(text(title, 26f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(sw, LinearLayout.LayoutParams(N9.ipx(66), N9.ipx(42)).apply { marginStart = N9.ipx(12) })
        list.addView(row, fullWidth())
        remember(row, title, 1)
        if (value != null) note(value)
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun label(s: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, N9.ipx(16), 0, N9.ipx(6)) }
        row.addView(text(s, 26f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        list.addView(row)
        remember(row, s, 1)
    }

    private fun sideways(v: View) = list.addView(android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(v)
    })

    // Previews of icon options, made off the main thread and kept while settings is open.
    private val previews = HashMap<String, android.graphics.Bitmap>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun fillChoices(strip: ChoiceStrip, samples: List<AppEntry>, styleOf: (Choice) -> String, shapeOf: (Choice) -> String) {
        val size = N9.ipx(56)
        for (ch in strip.choices) {
            val style = styleOf(ch); val shape = shapeOf(ch)
            val keys = samples.map { "$style|$shape|${it.key}" }
            if (keys.all { it in previews }) { ch.icons = keys.map { previews.getValue(it) }; continue }
            worker.execute {
                val icons = samples.mapIndexed { i, e -> synchronized(previews) { previews[keys[i]] } ?: Apps.previewIcon(e, style, shape, size).also { b -> synchronized(previews) { previews[keys[i]] = b } } }
                runOnUiThread { ch.icons = icons; strip.invalidate() }
            }
        }
    }

    private fun closeTo(list: List<Float>, v: Float) = list.indexOfFirst { kotlin.math.abs(it - v) < 0.01f }

    private val panelSizes = listOf(0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f)

    /** A panel's size (1 = the N9's); applied when home is next shown. */
    private fun sizeSlider(key: String, current: Float, glyph: StepSlider.Glyph, onMove: (Float) -> Unit = {}) {
        list.addView(StepSlider(this, panelSizes.size, closeTo(panelSizes, current).let { if (it < 0) 2 else it }, glyph,
            onMove = { i -> onMove(panelSizes[i]) }, onPick = { i -> Apps.setPanelScale(key, panelSizes[i]) }))
    }

    /** Appearance: a picture of the Applications view, with each option shown as it will look. */
    private fun appearance() {
        val preview = appsPreview
        label("Scaling")
        sizeSlider("scale_apps", Apps.appsScale, StepSlider.Glyph.ICON) { preview?.uiScale = it }

        label("Text size")
        val texts = listOf(0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f)
        list.addView(StepSlider(this, texts.size, closeTo(texts, Apps.textScale).let { if (it < 0) 3 else it }, StepSlider.Glyph.TEXT,
            onMove = { i -> preview?.textScale = texts[i] }, onPick = { i -> Apps.setTextScale(texts[i]) }))

        label("Icon size")
        val sizes = listOf(0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f)
        list.addView(StepSlider(this, sizes.size, closeTo(sizes, Apps.iconScale).let { if (it < 0) 3 else it }, StepSlider.Glyph.ICON,
            onMove = { i -> preview?.iconScale = sizes[i] },
            onPick = { i ->
                // Rebuilding every icon takes a moment: only once the finger lets go.
                Apps.setIconScale(sizes[i])
                preview?.icons = Apps.all.take(28).map { it.icon to it.label }
            }))

        // Icon shape and style: camera-style choices; the preview above shows the result.
        label("Icon shape")
        val shapes = Apps.SHAPES.entries.toList()
        sideways(Segments(this, shapes.map { it.value }, shapes.indexOfFirst { it.key == Apps.iconShape }.coerceAtLeast(0), fit = true) { i ->
            Apps.setIconShape(shapes[i].key)
            preview?.icons = Apps.all.take(28).map { it.icon to it.label }
        })
        label("Icons")
        val packs = IconPacks.installed(this)
        val styles = listOf(Apps.STYLE_N9 to "N9", Apps.STYLE_APP to "App icons") + packs.map { it.pkg to it.label }
        sideways(Segments(this, styles.map { it.second }, styles.indexOfFirst { it.first == Apps.iconStyle }.coerceAtLeast(0), fit = true) { i ->
            Apps.setIconStyle(styles[i].first)
            preview?.icons = Apps.all.take(28).map { it.icon to it.label }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun pct(f: Float) = "${(f * 100).toInt()}%"

    // ---- the page ----

    /** The page shown: null for the main list, else a section's name. */
    private var page: String? = null

    /** Opened from Home settings' Customize: the settings for one panel, as a sheet. */
    private val panel: String? get() = intent?.getStringExtra(EXTRA_PANEL)

    /** MSheet header: the view-header strip (72px), the title, and Done. */
    private fun sheetHeader(title: String) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(indent, 0, indent, 0)
            background = NineSlice(N9.bitmap(this@SettingsActivity, R.drawable.n9_view_header), 8, 8, 8, 8)
        }
        bar.addView(text(title, 32f, Color.WHITE, light = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        val into = pinned ?: list
        into.addView(bar, LinearLayout.LayoutParams(-1, N9.ipx(72)).apply { leftMargin = -indent; rightMargin = -indent })
    }

    private fun closeSheet() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold, R.anim.sheet_out)
    }

    /** What each panel's Customize sheet holds. */
    private fun panelContent(p: String) {
        when (p) {
            "launcher" -> {
                appearance()
                group("Applications")
                applications()
            }
            "switcher" -> openApps()
            "feed" -> feed()
            "events" -> {
                today()
                // Settings for all three views.
                group("System", labelled = true)
                switchItem("System notifications", null, EventStore.showOngoing) { EventStore.setShowOngoing(it) }
                note("Show undismissable system notifications in Today, like charging, USB options and downloads.")
                statusBar()
                switchItem("Scroll bars", null, Apps.showScrollbars) { Apps.setShowScrollbars(it) }
                switchItem("Gesture pill", null, Apps.showGesturePill) { Apps.setShowGesturePill(it) }
                if (Apps.showGesturePill) switchItem("Black bar behind the pill", null, Apps.blackNavBar) { Apps.setBlackNavBar(it) }
                pullDown()
            }
        }
    }

    /** What pulling down at the top of a view does (camera-settings style buttons). */
    private fun pullDown() {
        val values = listOf(Apps.PULL_SEARCH, Apps.PULL_QUICK_SETTINGS, Apps.PULL_OFF)
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, N9.ipx(12), 0, N9.ipx(12)) }
        row.addView(text("Pull down", 26f, Color.WHITE, bold = true))
        row.addView(Segments(this, listOf("Search", "Quick settings", "Off"), values.indexOf(Apps.pullDown).coerceAtLeast(0), fit = true) { i ->
            Apps.setPullDown(values[i])
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = N9.ipx(10) })
        list.addView(row)
        lastRow = null
    }

    private fun panelTitle(p: String) = getString(R.string.customize) + " " + when (p) {
        "launcher" -> getString(R.string.panel_apps); "switcher" -> getString(R.string.panel_open); else -> getString(R.string.panel_today)
    }

    private class Section(val name: String, val icon: Int, val summary: () -> String, val content: () -> Unit)

    private val sections: List<Section> by lazy { listOf(
        Section("Permissions", R.drawable.n9_settings_passcode, { permissionsSummary() }) { permissions() },
        Section("Appearance", R.drawable.n9_settings_personalize, { "Sizes, icons, gesture bar" }) { appearancePage() },
        Section("Status bar", R.drawable.n9_settings_battery, { if (Apps.systemStatusBar) "Android's status bar" else "N9 status bar" }) { statusBar() },
        Section("Applications", R.drawable.n9_settings_applications, {
            val n = Apps.hidden.count { k -> Apps.installed.any { it.key == k } }
            if (n == 0) "No hidden apps" else "$n hidden apps" }) { applications() },
        Section("Recents", R.drawable.n9_settings_description, {
            if (SystemRecents.granted()) "Matches the system's recent apps" else "Follows the apps you open" }) { openApps() },
        Section("Today", R.drawable.n9_settings_time_date, {
            when (Weather.mode) { Weather.MODE_OFF -> "Notifications, weather off"; else -> "Notifications, weather" } }) { today() },
        Section("Feed", R.drawable.n9_settings_notification, {
            val n = EventStore.rssSources.size; if (n == 0) "Social apps" else "Social apps, $n sources" }) { feed() },
    ) }

    private fun permissionsSummary(): String {
        val isHome = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName == packageName
        return if (isHome && notificationsAllowed() && Apps.hasUsageAccess()) "All allowed" else "Some need your OK"
    }

    private fun notificationsAllowed(): Boolean {
        val listener = ComponentName(this, EventsListenerService::class.java).flattenToString()
        return Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(listener) == true
    }

    private fun build() {
        val y = scroll.scrollY
        list.removeAllViews()
        val sheet = panel
        if (sheet != null) {
            list.setPadding(indent, N9.ipx(36), indent, N9.ipx(160))
            // Rows fade out as they scroll up under the preview, instead of being cut off.
            scroll.isVerticalFadingEdgeEnabled = true
            scroll.setFadingEdgeLength(N9.ipx(56))
            pinned?.removeAllViews()
            if (sheet == "feed") {
                // Feeds: its own page, a title and no preview.
                sheetHeader(getString(R.string.feeds))
                list.setPadding(indent, N9.ipx(12), indent, N9.ipx(160))
                panelContent(sheet)
                list.addView(View(this), LinearLayout.LayoutParams(1, N9.ipx(40)))
                scroll.post { scroll.scrollTo(0, y) }
                return
            }
            // The panel's preview, pinned under the header.
            val pv: View = when (sheet) {
                "launcher" -> HomePreview(this).apply {
                    uiScale = Apps.appsScale; iconScale = Apps.iconScale; textScale = Apps.textScale
                    icons = Apps.all.take(28).map { it.icon to it.label }
                }.also { appsPreview = it }
                "switcher" -> RecentsPreview(this).also { recentsPreview = it }
                else -> TodayPreview(this).also { todayPreview = it }
            }
            pinned?.addView(pv, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = -indent; rightMargin = -indent; bottomMargin = N9.ipx(12) })
            panelContent(sheet)
            list.addView(View(this), LinearLayout.LayoutParams(1, N9.ipx(40)))
            scroll.post { scroll.scrollTo(0, y) }
            return
        }
        val p = sections.firstOrNull { it.name == page }
        if (p == null) {
            header(getString(R.string.settings_name))
            list.addView(View(this), LinearLayout.LayoutParams(1, N9.ipx(8)))
            for (sec in sections) item(sec.name, sec.summary(), sec.icon) { page = sec.name; build(); scroll.post { scroll.scrollTo(0, 0) } }
        } else {
            header(p.name)
            p.content()
        }
        list.addView(View(this), LinearLayout.LayoutParams(1, N9.ipx(40)))
        scroll.post { scroll.scrollTo(0, y) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel != null) closeSheet()
        else if (page != null) { page = null; build(); scroll.post { scroll.scrollTo(0, 0) } }
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun permissions() {
        val isHome = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName == packageName
        item("Default home app", if (isHome) "Harmattan Home" else "Not set — tap to choose") {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        item("Notifications, media and feed", if (notificationsAllowed()) "Allowed" else "Needed for the Today view — tap to allow") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        item("Usage access", if (Apps.hasUsageAccess()) "Allowed" else "Lets the switcher follow apps opened outside home") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun appearancePage() {
        appearance()
        group("Gestures")
        switchItem("Black bar behind the gesture pill", if (Apps.blackNavBar) "The pill sits on a black bar" else "The pill floats over home",
            Apps.blackNavBar) { Apps.setBlackNavBar(it) }
        pullDown()
        switchItem("Scroll bars", "Shown while a view scrolls", Apps.showScrollbars) { Apps.setShowScrollbars(it) }
    }

    private fun statusBar() {
        switchItem("N9 status bar", null, !Apps.systemStatusBar) { Apps.setSystemStatusBar(!it); todayPreview?.n9StatusBar = !Apps.systemStatusBar }
        if (!Apps.systemStatusBar) {
            label("Size")
            sizeSlider("scale_statusbar", Apps.statusBarScale, StepSlider.Glyph.TEXT) { todayPreview?.statusScale = it }
            switchItem("Network name", "Next to the signal bars", Apps.showOperator) { Apps.setShowOperator(it); todayPreview?.showOperator = it }
        }
    }

    private fun applications() {
        val hiddenCount = Apps.hidden.count { k -> Apps.installed.any { it.key == k } }
        item("Hidden apps", if (hiddenCount == 0) "None — tap to choose apps to hide" else "$hiddenCount hidden — tap to change") {
            pickApps(this, "Hidden apps", "tap apps to hide them from Applications",
                Apps.installed.filter { it.pkg != packageName }.sortedBy { it.label.lowercase() }, Apps.hidden,
                keyOf = { it.key }, dim = true) { Apps.setHidden(it); build() }
        }
    }

    private fun openApps() {
        if (!Apps.hasUsageAccess() && !(Apps.matchRecents && SystemRecents.granted()))
            item("Allow usage access", "So Recents follows apps you open from elsewhere") {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        label("Large tiles up to")
        val counts = listOf(4, 6, 8, 10, 12)
        list.addView(Segments(this, counts.map { "$it" }, counts.indexOf(Apps.switcherLargeMax).let { if (it < 0) 3 else it }) { i ->
            Apps.setSwitcherLargeMax(counts[i]); recentsPreview?.largeMax = counts[i]
        })
        note("With more open apps than this, the switcher shows them smaller, three to a row.")
        group("System Recents")
        // On when Shizuku is allowed and the switch is on; turning it on walks through getting Shizuku.
        val matching = Apps.matchRecents && SystemRecents.granted()
        switchItem("Match system Recents", null, matching) { on ->
            if (!on) Apps.setMatchRecents(false)
            else {
                Apps.setMatchRecents(true)
                when {
                    SystemRecents.granted() -> {}
                    SystemRecents.running() -> SystemRecents.request()
                    SystemRecents.installed(this) -> {
                        Toast.makeText(this, "Start Shizuku, then turn this on again", Toast.LENGTH_LONG).show()
                        packageManager.getLaunchIntentForPackage(SystemRecents.SHIZUKU_PKG)?.let { startActivity(it) }
                    }
                    else -> {
                        Toast.makeText(this, "Matching the system Recents needs the Shizuku app", Toast.LENGTH_LONG).show()
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${SystemRecents.SHIZUKU_PKG}"))) }
                    }
                }
            }
        }
        if (matching) switchItem("Live previews", null, Apps.livePreviews) { Apps.setLivePreviews(it) }
    }

    private fun today() {
        // Without notification access Today can't show notifications or the feed: say so first.
        if (!notificationsAllowed()) item("Allow notification access", "Needed for notifications and the feed") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        label("Size")
        sizeSlider("scale_today", Apps.todayScale, StepSlider.Glyph.TEXT) { todayPreview?.scale = it }
        group("Weather")
        item("Weather", when (Weather.mode) {
            Weather.MODE_LOCATION -> "Where you are" + (Weather.city.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: "")
            Weather.MODE_CITY -> Weather.city
            else -> "Off — tap to show the weather beside the date"
        }) { chooseWeather() }
        if (Weather.mode != Weather.MODE_OFF) {
            // Temperature, with °C / °F beside it (camera-settings style).
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, N9.ipx(12), 0, N9.ipx(12)) }
            row.addView(text("Temperature", 26f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Segments(this, listOf("°C", "°F"), if (Weather.fahrenheit) 1 else 0) { i -> Weather.setUnit(i == 1); todayPreview?.invalidate() },
                LinearLayout.LayoutParams(N9.ipx(200), -2))
            list.addView(row)
            lastRow = null
        }
        group("Wallpaper")
        val hasWall = Wallpaper.exists(this)
        item(if (hasWall) "Change wallpaper" else "Wallpaper", null) { pickWallpaper() }
        if (hasWall) {
            val steps = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f)
            label("Darkness")
            list.addView(StepSlider(this, steps.size, closeTo(steps, Wallpaper.dim(this)).let { if (it < 0) 2 else it }, StepSlider.Glyph.ICON,
                onMove = { i -> todayPreview?.dim = steps[i] }, onPick = { i -> Wallpaper.setDim(this, steps[i]) }))
            note("How dark the wallpaper is behind notifications and the feed. Dark top keeps the date and weather readable.")
            val blurs = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            label("Blur")
            list.addView(StepSlider(this, blurs.size, closeTo(blurs, Wallpaper.blur(this)).let { if (it < 0) 2 else it }, StepSlider.Glyph.ICON,
                onMove = {}, onPick = { i -> Wallpaper.setBlur(this, blurs[i]) }))
            note("How blurred the wallpaper is behind Applications and Recents. Behind Today it's always sharp.")
            switchItem("Parallax", null, Wallpaper.parallax(this)) { Wallpaper.setParallax(this, it) }
            switchItem("Dark top", null, Wallpaper.topShade(this)) { Wallpaper.setTopShade(this, it); todayPreview?.invalidate() }
            switchItem("Phone wallpaper too", null, Wallpaper.shareWithSystem(this)) { on ->
                Wallpaper.setShareWithSystem(this, on)
                // Turning it on applies the current picture straight away.
                if (on) Thread {
                    val ok = Wallpaper.applyToSystem(this)
                    runOnUiThread { Toast.makeText(this, if (ok) "Set as the phone's wallpaper" else "Android didn't accept the wallpaper", Toast.LENGTH_SHORT).show() }
                }.start()
            }
            note("Also sets the picture as Android's home wallpaper, so Recents shows the same one. The lock screen keeps its own.")
            item("Remove wallpaper", null, drill = false) { Wallpaper.remove(this); build() }
        }
    }

    private fun feed() {
        val feedApps = EventStore.feedApps
        val feedNames = Apps.installed.filter { it.pkg in feedApps }.map { it.label }.distinct()
        item("Apps in the feed", if (feedNames.isEmpty()) "None" else feedNames.joinToString(", ")) { pickFeedApps() }
        note("Their notifications (new posts, comments, mentions) are kept in the feed. Facebook, Instagram, X " +
            "and Threads don't publish public feeds, so this is how their updates reach it.")
        val sources = EventStore.rssSources
        if (sources.isNotEmpty()) group("Sources")
        for (url in sources) sourceRow(url)
        if (EventStore.rssSources.any { it.contains("reddit.com") }) {
            item("Reddit icons", if (EventStore.redditClientId != null) "On — subreddit and user icons come from Reddit"
                else "Letters for now — tap to add a Reddit app ID for the real icons") { redditIcons() }
        }
        item("Add a source", null) { addAccount() }
    }

    // ---- dialogs ----

    private fun pickFeedApps() {
        // Known social apps first, then everything else that's installed; one cell per app.
        val byPkg = Apps.installed.filter { it.pkg != packageName }.associateBy { it.pkg }.values
        val social = byPkg.filter { it.pkg in EventStore.SOCIAL }.sortedBy { it.label.lowercase() }
        val others = byPkg.filter { it.pkg !in EventStore.SOCIAL }.sortedBy { it.label.lowercase() }
        pickApps(this, "Apps in the feed", "their notifications become feed posts", social + others,
            EventStore.feedApps, keyOf = { it.pkg }, dim = false) { EventStore.setFeedApps(it); build() }
    }

    /** A feed source: its icon, name and address; tap to remove it (and its posts). */
    private fun sourceRow(url: String) {
        val name = EventStore.sourceName(url)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = N9.ipx(72)
            setPadding(0, N9.ipx(6), 0, N9.ipx(6))
            setPadding(indent, N9.ipx(6), indent, N9.ipx(6))
            background = pressedBg()
            setOnClickListener {
                dialog().setTitle("Remove $name?")
                    .setMessage("Its posts are removed from the feed too.")
                    .setPositiveButton("Remove") { _, _ -> EventStore.removeSource(url); build() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        val icon = android.widget.ImageView(this).apply {
            setImageBitmap(Rss.savedIcon(this@SettingsActivity, url) ?: EventStore.masked(N9.bitmap(this@SettingsActivity, R.drawable.n9_app_rss)))
        }
        row.addView(icon, LinearLayout.LayoutParams(N9.ipx(48), N9.ipx(48)).apply { marginEnd = N9.ipx(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(name, N9.FONT_DEFAULT, Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        texts.addView(text(url.removePrefix("https://").removePrefix("www."), N9.FONT_XSMALL, N9.COLOR_INVERTED_SECONDARY_FOREGROUND).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        list.addView(row, fullWidth())
    }

    /** Reddit only hands out community icons to its API: ask for the user's own (free) app ID. */
    private fun redditIcons() {
        val input = EditText(this).apply {
            hint = "App ID (the code under the app's name)"
            setSingleLine()
            setText(EventStore.redditClientId.orEmpty())
        }
        val msg = "Reddit only gives subreddit and user icons to apps using its API. To use your own free app ID:\n\n" +
            "1. Signed in to Reddit, open reddit.com/prefs/apps\n" +
            "2. Tap \"create another app\", choose \"installed app\", give it any name, and use http://localhost as the redirect URI\n" +
            "3. Copy the ID shown under the app's name and paste it here\n\n" +
            "It's only used to read icons; nothing is posted and you aren't signed in here."
        val box = LinearLayout(this).apply { setPadding(N9.ipx(24), N9.ipx(8), N9.ipx(24), 0); addView(input) }
        dialog().setTitle("Reddit icons").setMessage(msg).setView(box)
            .setPositiveButton("Save") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isEmpty()) { EventStore.setRedditClientId(null); build(); return@setPositiveButton }
                Toast.makeText(this, "Checking the app ID…", Toast.LENGTH_SHORT).show()
                Thread {
                    val ok = runCatching { Rss.redditToken(id) != null }.getOrDefault(false)
                    runOnUiThread {
                        if (ok) { EventStore.setRedditClientId(id); Toast.makeText(this, "Reddit icons on — refreshing the feed", Toast.LENGTH_SHORT).show() }
                        else Toast.makeText(this, "Reddit didn't accept that app ID", Toast.LENGTH_LONG).show()
                        build()
                    }
                }.start()
            }
            .setNeutralButton("Turn off") { _, _ -> EventStore.setRedditClientId(null); build() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Weather beside the date: the phone's location, a city, or off. */
    /** Android's photo picker (no storage permission needed). */
    private fun pickWallpaper() {
        // Android's file chooser rather than the photo picker: the picker serves a filtered copy
        // (location removed) that came out damaged for some Samsung photos, cutting the wallpaper off.
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(i, REQ_WALLPAPER) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_WALLPAPER || resultCode != RESULT_OK || uri == null) return
        Thread {
            val ok = Wallpaper.save(this, uri)
            if (ok && Wallpaper.shareWithSystem(this)) Wallpaper.applyToSystem(this)
            runOnUiThread { if (!ok) Toast.makeText(this, "Couldn't use that picture", Toast.LENGTH_SHORT).show(); build() }
        }.start()
    }

    private fun chooseWeather() {
        dialog().setTitle("Weather")
            .setItems(arrayOf("Where I am", "Choose a city", "Off")) { _, i ->
                when (i) {
                    0 -> if (Weather.hasLocationPermission()) { Weather.useLocation(); build() }
                        else requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
                    1 -> askCity()
                    else -> { Weather.setOff(); build() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun askCity() {
        val input = EditText(this).apply { hint = "City, e.g. Auckland"; setSingleLine() }
        val box = LinearLayout(this).apply { setPadding(N9.ipx(24), N9.ipx(8), N9.ipx(24), 0); addView(input) }
        dialog().setTitle("Choose a city").setView(box)
            .setPositiveButton("Use") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                Weather.useCity(q) { found ->
                    Toast.makeText(this, found?.let { "Weather for $it" } ?: "Couldn't find $q", Toast.LENGTH_SHORT).show()
                    build()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (Weather.hasLocationPermission()) Weather.useLocation()
        else Toast.makeText(this, "Without location access, choose a city instead", Toast.LENGTH_LONG).show()
        build()
    }

    companion object {
        private const val REQ_LOCATION = 0x7E
        private const val REQ_WALLPAPER = 0x7F
        const val EXTRA_PANEL = "panel"
    }

    private fun addAccount() {
        val kinds = Rss.Kind.values()
        dialog().setTitle("Add posts from")
            .setItems(kinds.map { it.label }.toTypedArray()) { _, i -> askAccount(kinds[i]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun askAccount(kind: Rss.Kind) {
        val input = EditText(this).apply {
            hint = kind.hint
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(this).apply { setPadding(N9.ipx(24), N9.ipx(8), N9.ipx(24), 0); addView(input) }
        dialog().setTitle(kind.label).setView(box)
            .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                Toast.makeText(this, "Looking up $v…", Toast.LENGTH_SHORT).show()
                Rss.resolve(this, kind, v) { found ->
                    runOnUiThread {
                        val url = found.url
                        if (url == null) Toast.makeText(this, found.error, Toast.LENGTH_LONG).show()
                        else {
                            EventStore.addSource(url, found.title, found.feed?.items.orEmpty())
                            Toast.makeText(this, "Added ${found.title ?: v}", Toast.LENGTH_SHORT).show()
                            build()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
