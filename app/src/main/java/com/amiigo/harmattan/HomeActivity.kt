package com.amiigo.harmattan

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Rect

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout

/**
 * Harmattan home. Views on the ring, in swipe order: Launcher → Switcher → Events.
 * The N9 status bar replaces Android's; the navigation bar is left to the system.
 */
class HomeActivity : Activity() {
    companion object {
        const val LAUNCHER = 0
        const val SWITCHER = 1
        const val EVENTS = 2

        /** Go back into Home settings on this panel when home is shown again (after the sheet, or a rebuild). */
        var reopenCustomize: Int? = null
    }

    private lateinit var root: FrameLayout
    private lateinit var home: SwipeHome
    private lateinit var statusBar: StatusBarView
    private lateinit var gradient: GradientOverlay
    private lateinit var launcher: LauncherPanel
    private lateinit var switcher: SwitcherPanel
    private lateinit var events: EventsPanel
    private lateinit var animator: LaunchAnimator
    private var leftHome = false
    private var resumed = false

    // ---- Home settings: the panels zoomed out (see CustomizeOverlay) ----
    private lateinit var customize: CustomizeOverlay
    private lateinit var wallpaper: WallpaperView
    private lateinit var search: SearchOverlay

    /** Off to one of home's own sheets (Feed): coming back is not a return from an app. */
    private var inOwnSheet = false

    /** The feed's settings, as their own sheet over Today. */
    fun openFeedSheet() {
        inOwnSheet = true
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PANEL, "feed"))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.sheet_in, R.anim.hold)
    }

    fun openSearch() {
        if (customizing) return
        search.top0 = home.top
        search.open()
    }

    // Pinch in (two fingers together) anywhere on Applications or Today: the Home editor.
    // Recents has its own pinch (large / small tiles), which opens it past the smallest layout.
    private var pinching = false
    private var pinchTotal = 1f
    private var lastEvent: android.view.MotionEvent? = null
    private val pinch by lazy {
        android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean {
                pinching = true; pinchTotal = 1f
                // The views under the fingers stop what they were doing (scroll, hold).
                lastEvent?.let { e ->
                    val c = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    super@HomeActivity.dispatchTouchEvent(c); c.recycle()
                }
                return true
            }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean { pinchTotal *= d.scaleFactor; return true }
            override fun onScaleEnd(d: android.view.ScaleGestureDetector) { if (pinchTotal < 0.8f) enterCustomize(home.current) }
        })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::search.isInitialized && !search.isOpen && !customizing && home.current != SWITCHER) {
            lastEvent = ev
            pinch.onTouchEvent(ev)
            if (pinching) {
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) pinching = false
                return true
            }
        } else pinching = false
        return super.dispatchTouchEvent(ev)
    }
    var customizing = false
        private set
    private var zoom: android.animation.ValueAnimator? = null
    private val panelNames get() = arrayOf(getString(R.string.panel_apps), getString(R.string.panel_open), getString(R.string.panel_today))
    private val panelKeys = arrayOf("launcher", "switcher", "events")
    private val ZOOM = 0.6f

    /** Zoom out to the three panels, [page] (default Today) in the middle. */
    fun enterCustomize(page: Int = EVENTS, animated: Boolean = true) {
        if (customizing) return
        if (search.isOpen) search.close(false)
        customizing = true
        launcher.exitEditMode(); switcher.exitEditMode(); events.hideToolbar()
        home.snapTo(page, false)
        home.pagingEnabled = false
        home.gap = N9.px(28f) / ZOOM
        home.showNeighbours = true
        home.pivotX = home.width / 2f; home.pivotY = home.height / 2f
        customize.visibility = View.VISIBLE
        customize.title = panelNames[page]
        // The wallpaper goes with Today into its card; Applications and Recents stay on black.
        // Drawn to line up exactly with the full-screen wallpaper (no jump when they swap).
        events.wallpaperLayer.apply {
            val k = (events.parent as? ScaledFrame)?.k ?: 1f
            screenTop = home.top / k
            screenH = root.height / k
            topH = wallpaper.topH / k
            phase = wallpaper.phase
            refresh(); active = true
        }
        wallpaper.active = false
        placeCustomize()
        zoomTo(1f, animated)
    }

    fun exitCustomize(animated: Boolean = true) {
        if (!customizing) return
        customizing = false
        zoomTo(0f, animated) {
            home.showNeighbours = false; home.gap = 0f
            wallpaper.active = true; events.wallpaperLayer.active = false
            home.pagingEnabled = true
            customize.visibility = View.GONE
        }
    }

    /** Where the zoomed panels sit on screen, for the overlay's frames and taps. */
    private fun placeCustomize() {
        val l = home.left + home.pivotX * (1 - ZOOM)
        val t = home.top + home.pivotY * (1 - ZOOM)
        customize.centre.set(l, t, l + home.width * ZOOM, t + home.height * ZOOM)
        customize.step = (home.width + home.gap) * ZOOM
    }

    private fun zoomTo(target: Float, animated: Boolean, end: () -> Unit = {}) {
        zoom?.cancel()
        val from = customize.shown
        fun set(f: Float) {
            val k = 1f + (ZOOM - 1f) * f
            home.scaleX = k; home.scaleY = k
            statusBar.alpha = 1f - f; gradient.alpha = 1f - f
            customize.shown = f
        }
        if (!animated) { set(target); end(); return }
        zoom = android.animation.ValueAnimator.ofFloat(from, target).apply {
            duration = 300; interpolator = Ease.outExpo
            addUpdateListener { set(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { end() }
            })
            start()
        }
    }

    /** The overlay page of settings for the middle panel (SettingsActivity as a sheet). */
    private fun openCustomizeSheet() {
        val page = home.current
        reopenCustomize = page
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PANEL, panelKeys[page]))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.sheet_in, R.anim.hold)
    }


    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Apps.reload() // calendar icon shows today's date
            events.rebuild()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        N9.init(this)
        Apps.init(this)
        // App previews (screenshots) were removed; drop any pictures left from them.
        runCatching { java.io.File(filesDir, "snaps").deleteRecursively() }
        EventStore.init(this)
        MediaWatcher.init(this)
        Weather.init(this)

        root = FrameLayout(this).apply { setBackgroundColor(N9.COLOR_INVERTED_BACKGROUND) }
        home = SwipeHome(this)
        launcher = LauncherPanel(this)
        switcher = SwitcherPanel(this)
        events = EventsPanel(this)
        // Applications and Today are drawn at their own sizes (Home settings → Size).
        home.addPage(ScaledFrame(this, launcher, Apps.appsScale))
        home.addPage(switcher)
        home.addPage(ScaledFrame(this, events, Apps.todayScale))
        statusBar = StatusBarView(this)
        gradient = GradientOverlay(this)

        val sb = N9.ipx(N9.STATUSBAR)
        wallpaper = WallpaperView(this)
        root.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))
        root.addView(home, FrameLayout.LayoutParams(-1, -1).apply { topMargin = sb })
        // The Home editor's side panels sit outside home's bounds. Set once: switching this as the
        // editor opened flashed a black frame. (Off-screen panels are hidden, so nothing spills.)
        home.clipChildren = false; root.clipChildren = false
        root.addView(gradient, FrameLayout.LayoutParams(-1, N9.ipx(10f)).apply { topMargin = sb })
        root.addView(statusBar, FrameLayout.LayoutParams(-1, -2))
        customize = CustomizeOverlay(this,
            onSwipe = { dir -> home.snapTo(Math.floorMod(home.current + dir, 3), true) },
            onTapCentre = { exitCustomize() },
            onCustomize = { openCustomizeSheet() },
            onAllSettings = { reopenCustomize = home.current; openHomeSettings(this) },
        ).apply { visibility = View.GONE }
        root.addView(customize, FrameLayout.LayoutParams(-1, -1))
        // Search sits over the views, under the status bar.
        search = SearchOverlay(this)
        root.addView(search, root.indexOfChild(statusBar), FrameLayout.LayoutParams(-1, -1))
        if (Apps.systemStatusBar) statusBar.visibility = View.GONE
        setContentView(root)
        useFastestRefreshRate()
        animator = LaunchAnimator(this, root, home)
        // The Search icon (Android's search app has the N9 Search role) opens the N9 search here.
        launcher.onOpen = { e, v, r -> if (e.role == "search") openSearch() else animator.open(e, v, r) }
        switcher.onOpen = { e, v -> animator.open(e, v, Rect(0, 0, v.width, v.height)) }

        SystemRecents.onPermissionResult { root.post { Apps.refreshSystemRecents() } }
        home.onPageSettled = { page -> onPage(page) }
        // Sharp behind Today, blurred towards the other panels as they slide in.
        home.onOffsetChanged = { o ->
            wallpaper.blurAmount = WallpaperView.distance(o, EVENTS, 3)
            wallpaper.ring(o, 3)
        }
        home.snapTo(LAUNCHER, false)
        events.onScroll = { pos -> wallpaper.todayScroll = pos * Apps.todayScale }
        EventStore.addListener { statusBar.notifier = EventStore.unseen }

        setupWindow()
        val f = IntentFilter().apply { addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dateReceiver, f, Context.RECEIVER_EXPORTED) else registerReceiver(dateReceiver, f)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(dateReceiver) }
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        val w = window
        // The gesture pill on a black bar (as Android draws it), or floating over home (Home settings).
        w.navigationBarColor = if (Apps.blackNavBar) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            w.insetsController?.let {
                if (Apps.systemStatusBar) {
                    // Android's own status bar, light icons over the black home.
                    it.show(WindowInsets.Type.statusBars())
                    it.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                } else {
                    it.hide(WindowInsets.Type.statusBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                // The gesture pill (and its bar): shown, or hidden with the gestures still working.
                if (Apps.showGesturePill) it.show(WindowInsets.Type.navigationBars())
                else {
                    it.hide(WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } else {
            w.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bottom: Int
            val cutoutTop: Int
            val hole = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.boundingRects
                ?.filter { it.top <= 0 && it.height() > 0 }?.minByOrNull { it.top } else null
            if (Build.VERSION.SDK_INT >= 30) {
                bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                cutoutTop = insets.getInsets(WindowInsets.Type.displayCutout()).top
            } else {
                bottom = insets.systemWindowInsetBottom
                cutoutTop = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            }
            // The status bar fills the camera cutout band, its icons in line with the hole.
            statusBar.bandHeight = cutoutTop
            statusBar.hole = hole
            if (Build.VERSION.SDK_INT >= 31) statusBar.cornerRadius = maxOf(
                insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0,
                insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0)
            val sb = if (Apps.systemStatusBar && Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            else statusBar.barHeight
            (home.layoutParams as FrameLayout.LayoutParams).topMargin = sb
            (gradient.layoutParams as FrameLayout.LayoutParams).topMargin = sb
            home.requestLayout(); gradient.requestLayout()
            // In the scaled panels' own (smaller) units.
            launcher.bottomInset = (bottom / Apps.appsScale).toInt()
            switcher.bottomInset = bottom
            events.bottomInset = (bottom / Apps.todayScale).toInt()
            insets
        }
    }

    private fun onPage(page: Int) {
        if (customizing) customize.title = panelNames[page]
        if (page != LAUNCHER) launcher.exitEditMode()
        if (page != SWITCHER) switcher.exitEditMode()
        if (page != EVENTS) events.hideToolbar()
        switcher.visibleNow = page == SWITCHER && resumed
        if (page == EVENTS) events.onShown()
    }

    override fun onStop() {
        super.onStop()
        search.close(animated = false)
        // Off to the Customize sheet: home stays as it is (zoomed out), no return animation.
        if (customizing || reopenCustomize != null || inOwnSheet) { leftHome = false; return }
        animator.reset()
        // Screen-off also stops the activity; only count leaving for another app.
        leftHome = getSystemService(android.os.PowerManager::class.java).isInteractive
        launcher.exitEditMode()
        switcher.exitEditMode()
        // While home is hidden, set up the return: the switcher page, zoomed and dimmed around the app's
        // tile, so the system brings back a home that's already in place.
        if (leftHome) {
            // The app just opened moves to the front of the system Recents: take that order now, tiles
            // straight into place, so the card shrinks into the tile's final spot (not where it was).
            switcher.grid.placeInstantly = true
            Apps.refreshSystemRecentsNow()
            // Android pauses layout while home is hidden: lay the new order out now, so the tile is
            // measured where it will be.
            if (root.width > 0) {
                root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
                root.layout(root.left, root.top, root.right, root.bottom)
            }
        }
        if (leftHome && Apps.recentApps().isNotEmpty()) {
            home.snapTo(SWITCHER, false)
            val e = animator.lastOpened ?: Apps.lastUsed()
            val tile = e?.let { switcher.tileFor(it) }
            if (e != null && tile != null) {
                switcher.grid.skipIncoming(e)
                root.post { if (!resumed) animator.prepare(e, tile) }
            }
        }
    }

    private var seenLayout = -1

    override fun onResume() {
        super.onResume()
        // Interface scale changed in Home settings: rebuild everything at the new size.
        if (seenLayout == -1) seenLayout = Apps.layoutVersion
        else if (seenLayout != Apps.layoutVersion) {
            seenLayout = Apps.layoutVersion
            N9.init(this); Apps.reload(); recreate(); return
        }
        resumed = true
        inOwnSheet = false
        wallpaper.topH = statusBar.barHeight + N9.px(110f) * Apps.todayScale
        wallpaper.refresh()
        wallpaper.blurAmount = WallpaperView.distance(home.offset, EVENTS, 3)
        wallpaper.ring(home.offset, 3)
        reopenCustomize?.let { p ->
            reopenCustomize = null
            if (customizing) { customize.title = panelNames[home.current]; zoomTo(1f, false) }
            else root.post { enterCustomize(p, animated = false) }
        }
        setupWindow()
        Apps.syncRecentsFromUsage()
        Apps.refreshSystemRecents()
        switcher.refreshPreviews() // the app just left has a new snapshot
        statusBar.refresh()
        if (leftHome) {
            leftHome = false
            // On the N9, leaving an app put you in the open-applications view, with the
            // app's window settling into its place in the grid.
            val last = animator.lastOpened ?: Apps.lastUsed()
            if (Apps.recentApps().isNotEmpty()) home.snapTo(SWITCHER, false)
            minimise(last)
        }
        switcher.visibleNow = home.current == SWITCHER
        if (home.current == EVENTS) events.onShown()
    }

    /** The app's window card shrinks into its switcher tile; with no tile, home zooms back out. */
    private fun minimise(e: AppEntry?) {
        val tile = e?.let { switcher.tileFor(it) }
        if (e == null || tile == null || home.current != SWITCHER) { animator.homeZoomOut(); return }
        switcher.grid.skipIncoming(e)
        animator.close(e, tile)
    }

    /** Home's window has finished sliding/fading in: now the app can shrink into its tile, visibly. */
    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        animator.playClose()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        switcher.visibleNow = false
    }

    /** Demo mode switched: Applications re-reads its (filtered) list. */
    fun demoChanged() = launcher.grid.sync()

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Demo.handle(this, intent)
        if (intent?.hasCategory(Intent.CATEGORY_HOME) != true) return
        if (search.isOpen) { search.close(); return }
        val wasVisible = resumed && hasWindowFocus()
        if (customizing) { reopenCustomize = null; exitCustomize(wasVisible); return }
        launcher.exitEditMode(); switcher.exitEditMode()
        if (wasVisible) {
            // Home pressed while already home: go to the applications grid, then to its top.
            if (home.current != LAUNCHER) home.snapTo(LAUNCHER, true) else launcher.pannable.scrollToTop(true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            search.isOpen -> search.close()
            customizing -> exitCustomize()
            launcher.folderOpen -> launcher.closeFolder()
            launcher.inEditMode -> launcher.exitEditMode()
            switcher.inEditMode -> switcher.exitEditMode()
            home.current != LAUNCHER -> home.snapTo(LAUNCHER, true)
        }
    }

    /**
     * Run at the screen's fastest refresh rate (e.g. 120 Hz) at the current resolution. The N9 itself
     * was 60 Hz; animations here are time-based, so they keep their timing and only get smoother.
     */
    private fun useFastestRefreshRate() {
        val d = if (android.os.Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        d ?: return
        val cur = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
            preferredRefreshRate = best.refreshRate
        }
    }
}

/**
 * MOverlayStyle#GradientOverlay + MWidgetStyle#Gradient: the "gradient" image (black 40% → 0%)
 * at 100% x 1mm, offset by the status bar height, so content fades as it scrolls under the bar.
 */
class GradientOverlay(ctx: Context) : View(ctx) {
    private val bmp = N9.bitmap(ctx, R.drawable.n9_gradient)
    private val paint = Apps.iconPaint()
    private val dst = Rect()
    override fun onDraw(canvas: Canvas) {
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bmp, null, dst, paint)
    }
}
