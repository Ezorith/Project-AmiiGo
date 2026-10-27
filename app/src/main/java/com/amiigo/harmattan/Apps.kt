package com.amiigo.harmattan

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import java.util.Calendar

/** A launcher folder (SwipeLauncherFolder). Name defaults to qtn_home_launcher_folder_default_name. */
class Folder(val id: String, var name: String, val apps: MutableList<AppEntry>)

sealed class LItem {
    abstract val key: String
    class App(val e: AppEntry) : LItem() { override val key get() = "a:" + e.key }
    class Dir(val f: Folder) : LItem() { override val key get() = "f:" + f.id }
}

class AppEntry(
    val component: ComponentName,
    val user: UserHandle,
    val label: String,
    val icon: Bitmap,
    val role: String?,
    val removable: Boolean,
    /** Average icon colour, used to tint switcher tiles. */
    val tint: Int,
) {
    val key: String get() = Apps.keyOf(component, user)
    val pkg: String get() = component.packageName
}

/**
 * Installed apps, their launcher order, and the "recently open" list shown by the switcher.
 */
object Apps {
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager
    private val main = Handler(Looper.getMainLooper())

    var all: List<AppEntry> = emptyList()
        private set
    private val listeners = mutableListOf<() -> Unit>()
    private val recentListeners = mutableListOf<() -> Unit>()

    // ---- N9 icon theme ----

    /** N9 app icons (icon-l-*) and names for Android apps that play the same role. */
    private val ROLE_ICON = mapOf(
        "telephony" to R.drawable.n9_app_telephony,
        "contacts" to R.drawable.n9_app_contacts,
        "search" to R.drawable.n9_app_search,
        "browser" to R.drawable.n9_app_browser,
        "conversation" to R.drawable.n9_app_conversation,
        "email" to R.drawable.n9_app_email,
        "calendar" to R.drawable.n9_app_calendar,
        "clock" to R.drawable.n9_app_clock,
        "camera" to R.drawable.n9_app_camera,
        "gallery" to R.drawable.n9_app_gallery,
        "music" to R.drawable.n9_app_music,
        "videos" to R.drawable.n9_app_videos_tv,
        "maps" to R.drawable.n9_app_maps,
        "store" to R.drawable.n9_app_store,
        "facebook" to R.drawable.n9_app_facebook,
        "twitter" to R.drawable.n9_app_twitter,
        "settings" to R.drawable.n9_app_settings,
        "calculator" to R.drawable.n9_app_calculator,
        "documents" to R.drawable.n9_app_documents,
        "notes" to R.drawable.n9_app_notes,
        "youtube" to R.drawable.n9_app_youtube,
        "skype" to R.drawable.n9_app_skype,
        "firefox" to R.drawable.n9_app_firefox,
        "rss" to R.drawable.n9_app_rss,
    )

    /** English names from the N9's .desktop files / launcher. */
    private val ROLE_NAME = mapOf(
        "telephony" to "Phone", "contacts" to "Contacts", "search" to "Search", "browser" to "Web",
        "conversation" to "Messages", "email" to "Mail", "calendar" to "Calendar", "clock" to "Clock",
        "camera" to "Camera", "gallery" to "Gallery", "music" to "Music", "videos" to "Video",
        "maps" to "Maps", "store" to "Store", "facebook" to "Facebook", "twitter" to "Twitter",
        "settings" to "Settings", "calculator" to "Calculator", "notes" to "Notes",
        "youtube" to "YouTube", "skype" to "Skype", "firefox" to "Firefox",
    )

    /**
     * A stand-in app with the N9's own icon and name for [role] ([res] and [name] for icons the
     * roles don't cover), for the demo grid.
     */
    fun demoEntry(role: String, res: Int? = null, name: String? = null): AppEntry? {
        val r = res ?: (if (role == "calendar") calendarIcon() else ROLE_ICON[role]) ?: return null
        val size = all.firstOrNull()?.icon?.width ?: N9.ipx(80)
        val art = Bitmap.createScaledBitmap(N9.bitmap(app, r), size, size, true)
        return AppEntry(android.content.ComponentName(Demo.PKG, role), android.os.Process.myUserHandle(),
            name ?: ROLE_NAME[role] ?: role, art, role, false, averageColor(art))
    }

    /** /etc/xdg/meegotouchhome-nokia/launcherbuttons.data, by role. */
    val DEFAULT_ORDER = listOf(
        "telephony", "contacts", "search", "browser", "conversation", "email", "calendar", "clock",
        "camera", "gallery", "music", "videos", "maps", "store", "facebook", "twitter", "settings",
        "calculator", "documents", "rss", "notes", "youtube", "skype",
    )

    private val STATIC_ROLES = mapOf(
        "com.google.android.dialer" to "telephony", "com.android.dialer" to "telephony", "com.samsung.android.dialer" to "telephony",
        "com.google.android.contacts" to "contacts", "com.android.contacts" to "contacts", "com.samsung.android.app.contacts" to "contacts",
        "com.google.android.googlequicksearchbox" to "search",
        "com.android.chrome" to "browser", "com.sec.android.app.sbrowser" to "browser",
        "com.google.android.apps.messaging" to "conversation", "com.samsung.android.messaging" to "conversation",
        "com.google.android.gm" to "email", "com.samsung.android.email.provider" to "email",
        "com.google.android.calendar" to "calendar", "com.samsung.android.calendar" to "calendar",
        "com.google.android.deskclock" to "clock", "com.android.deskclock" to "clock", "com.sec.android.app.clockpackage" to "clock",
        "com.google.android.GoogleCamera" to "camera", "com.sec.android.app.camera" to "camera", "com.android.camera2" to "camera",
        "com.google.android.apps.photos" to "gallery", "com.sec.android.gallery3d" to "gallery", "com.android.gallery3d" to "gallery",
        "com.google.android.apps.youtube.music" to "music", "com.sec.android.app.music" to "music",
        "com.google.android.videos" to "videos",
        "com.google.android.apps.maps" to "maps",
        "com.android.vending" to "store",
        "com.facebook.katana" to "facebook",
        "com.twitter.android" to "twitter",
        "com.android.settings" to "settings",
        "com.google.android.calculator" to "calculator", "com.sec.android.app.popupcalculator" to "calculator", "com.android.calculator2" to "calculator",
        "com.google.android.apps.docs" to "documents",
        "com.google.android.keep" to "notes", "com.samsung.android.app.notes" to "notes",
        "com.google.android.youtube" to "youtube",
        "com.skype.raider" to "skype",
        "org.mozilla.firefox" to "firefox",
        "com.google.android.apps.magazines" to "rss",
    )

    private var dynamicRoles = emptyMap<String, String>()

    val n9Icons: Boolean get() = prefs.getBoolean("n9_icons", true)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("home", Context.MODE_PRIVATE)
        launcherApps = app.getSystemService(LauncherApps::class.java)
        userManager = app.getSystemService(UserManager::class.java)
        recents = loadRecents()
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) { removeRecent(packageName); reload() }
            override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
            override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
            override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, r: Boolean) = reload()
            override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, r: Boolean) = reload()
        }, main)
        reload()
    }

    /** Icon style: the N9 originals for matching apps (default), apps' own icons, or an icon pack. */
    const val STYLE_N9 = "n9"
    const val STYLE_APP = "app"
    val iconStyle: String get() = iconPack ?: if (n9Icons) STYLE_N9 else STYLE_APP
    fun setIconStyle(style: String) {
        val pack = style.takeIf { it != STYLE_N9 && it != STYLE_APP }
        prefs.edit().putString("icon_pack", pack).putBoolean("n9_icons", style == STYLE_N9).apply()
        loadedPack = null
        reload()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun addRecentListener(l: () -> Unit) { recentListeners += l }

    fun keyOf(c: ComponentName, u: UserHandle) = c.flattenToString() + "#" + userManager.getSerialNumberForUser(u)

    private fun computeDynamicRoles(): Map<String, String> {
        val m = HashMap<String, String>()
        val pm = app.packageManager
        runCatching { app.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let { m[it] = "telephony" }
        runCatching { Telephony.Sms.getDefaultSmsPackage(app) }.getOrNull()?.let { m[it] = "conversation" }
        fun resolve(i: Intent) = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeIf { it != "android" }
        resolve(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://nokia.com")))?.let { m[it] = "browser" }
        resolve(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))?.let { m[it] = "camera" }
        return m
    }

    fun roleOf(pkg: String): String? = dynamicRoles[pkg] ?: STATIC_ROLES[pkg]

    fun reload() {
        dynamicRoles = computeDynamicRoles()
        val profiles = userManager.userProfiles
        val infos = ArrayList<LauncherActivityInfo>()
        for (u in profiles) infos += launcherApps.getActivityList(null, u)
        // The user's default apps (dialer, SMS, browser, camera) claim their N9 icon first.
        infos.sortBy { if (dynamicRoles.containsKey(it.componentName.packageName)) 0 else 1 }
        val seenRoles = HashSet<String>()
        val pack = pack()
        val built = infos.mapNotNull { info ->
            if (info.componentName.packageName == app.packageName && info.componentName.className.endsWith(".HomeActivity")) return@mapNotNull null
            var role = roleOf(info.componentName.packageName)
            if (role != null && info.user != Process.myUserHandle()) role = null
            // Only the first app of each role gets the N9 icon, so two browsers stay distinguishable.
            if (role != null && !seenRoles.add(role)) role = null
            build(info, role, pack)
        }
        installed = built
        val hide = hidden
        all = sortByOrder(built.filter { it.key !in hide })
        listeners.forEach { it() }
        recentListeners.forEach { it() }
    }

    private fun build(info: LauncherActivityInfo, role: String?, pack: IconPacks.Loaded?): AppEntry {
        // LauncherButtonStyle icon-size: 8mm, times the user's icon size setting.
        // Drawn at the Applications panel's size so a larger panel doesn't blur them.
        val size = N9.ipx(80f * iconScale * appsScale.coerceAtLeast(1f))
        val (raw, useN9) = iconFor(info, role, pack, n9Icons, iconShape, size)
        // Work-profile badge goes on after shaping.
        val badged = app.packageManager.getUserBadgedIcon(android.graphics.drawable.BitmapDrawable(app.resources, raw), info.user)
        val icon = if (badged is android.graphics.drawable.BitmapDrawable) badged.bitmap else render(badged, size)
        val label = if (useN9) ROLE_NAME[role] ?: info.label.toString() else info.label.toString()
        val flags = info.applicationInfo.flags
        val removable = flags and ApplicationInfo.FLAG_SYSTEM == 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        val e = AppEntry(info.componentName, info.user, label, icon, if (useN9) role else null, removable && info.componentName.packageName != app.packageName, averageColor(icon))
        sources[e.key] = info to role
        return e
    }

    /** The icon with the given options; second is whether the N9 original art was used. */
    private fun iconFor(info: LauncherActivityInfo, role: String?, pack: IconPacks.Loaded?, n9: Boolean, shape: String, size: Int): Pair<Bitmap, Boolean> {
        val packIcon = pack?.iconFor(info.componentName)
        val useN9 = packIcon == null && n9 && role != null && ROLE_ICON.containsKey(role)
        val raw: Bitmap = when {
            // An icon pack's own icons are used as drawn.
            packIcon != null -> render(packIcon, size)
            useN9 -> {
                val res = if (role == "calendar") calendarIcon() else ROLE_ICON.getValue(role!!)
                val art = Bitmap.createScaledBitmap(N9.bitmap(app, res), size, size, true)
                // N9 art is already the N9 squircle; any other chosen shape cuts it to that shape.
                if (shape == SHAPE_N9 || shape == SHAPE_ORIGINAL) art else applyMask(art, shapeMask(shape, size))
            }
            else -> {
                val d = info.getIcon(app.resources.displayMetrics.densityDpi)
                pack?.themed(d, size) ?: if (shape == SHAPE_ORIGINAL) original(d, size) else applyMask(fill(d, size), shapeMask(shape, size))
            }
        }
        return raw to useN9
    }

    /** What each app was built from, so settings can preview other icon options. */
    private val sources = HashMap<String, Pair<LauncherActivityInfo, String?>>()
    private val previewPacks = HashMap<String, IconPacks.Loaded?>()

    /**
     * [e]'s icon as it would look with [style] ([STYLE_N9], [STYLE_APP] or an icon pack's package)
     * and [shape]. Safe to call off the main thread.
     */
    fun previewIcon(e: AppEntry, style: String, shape: String, size: Int): Bitmap {
        val (info, role) = sources[e.key] ?: return e.icon
        val pack = if (style == STYLE_N9 || style == STYLE_APP) null
            else synchronized(previewPacks) { previewPacks.getOrPut(style) { runCatching { IconPacks.Loaded(app, style) }.getOrNull() } }
        return runCatching { iconFor(info, role, pack, style == STYLE_N9, shape, size).first }.getOrDefault(e.icon)
    }

    private fun calendarIcon(): Int {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        return app.resources.getIdentifier("n9_app_calendar_%02d".format(day), "drawable", app.packageName)
            .takeIf { it != 0 } ?: R.drawable.n9_app_calendar
    }

    // ---- icon appearance settings ----

    const val SHAPE_N9 = "n9"
    const val SHAPE_CIRCLE = "circle"
    const val SHAPE_ROUNDED = "rounded"
    const val SHAPE_SQUARE = "square"
    const val SHAPE_TEARDROP = "teardrop"
    const val SHAPE_ORIGINAL = "original"
    val SHAPES = linkedMapOf(
        SHAPE_N9 to "N9", SHAPE_CIRCLE to "Circle", SHAPE_ROUNDED to "Rounded",
        SHAPE_SQUARE to "Square", SHAPE_TEARDROP to "Teardrop", SHAPE_ORIGINAL to "App's own",
    )
    val iconShape: String get() = prefs.getString("icon_shape", SHAPE_N9)!!
    fun setIconShape(s: String) { prefs.edit().putString("icon_shape", s).apply(); reload() }

    /** Icon size relative to the N9's 8mm (80px) launcher icons. */
    val iconScale: Float get() = prefs.getFloat("icon_scale", 1f)
    fun setIconScale(f: Float) { prefs.edit().putFloat("icon_scale", f).apply(); reload() }

    /** Package name of the chosen icon pack, or null. */
    val iconPack: String? get() = prefs.getString("icon_pack", null)
    private var loadedPack: IconPacks.Loaded? = null
    private fun pack(): IconPacks.Loaded? {
        val p = iconPack ?: return null
        loadedPack?.takeIf { it.pkg == p }?.let { return it }
        return runCatching { IconPacks.Loaded(app, p) }.getOrNull().also { loadedPack = it }
    }

    /** Whether the status bar shows the network operator's name next to the signal bars. */
    val showOperator: Boolean get() = prefs.getBoolean("show_operator", true)
    fun setShowOperator(on: Boolean) { prefs.edit().putBoolean("show_operator", on).apply() }

    /** Text size across the panels (1 = the N9's). Applied when home is next shown. */
    val textScale: Float get() = prefs.getFloat("text_scale", 1f)
    fun setTextScale(f: Float) { prefs.edit().putFloat("text_scale", f).putInt("layout_version", layoutVersion + 1).apply() }

    /** Interface scale (1 = the N9's 480px layout fills the screen width). Applied on the next start. */
    // Each panel has its own size (Home settings): 1 = the N9's.
    val statusBarScale: Float get() = prefs.getFloat("scale_statusbar", 1f)
    val appsScale: Float get() = prefs.getFloat("scale_apps", 1f)
    val switcherScale: Float get() = prefs.getFloat("scale_switcher", 1f)
    val todayScale: Float get() = prefs.getFloat("scale_today", 1f)
    fun setPanelScale(key: String, f: Float) {
        prefs.edit().putFloat(key, f).putInt("layout_version", layoutVersion + 1).apply()
        if (key == "scale_apps") reload() // icons are drawn at the panel's size, to stay sharp
    }

    /** Open apps tiles show Android's snapshots of the apps (needs Shizuku). */
    val livePreviews: Boolean get() = prefs.getBoolean("live_previews", true)
    fun setLivePreviews(on: Boolean) { prefs.edit().putBoolean("live_previews", on).apply(); recentListeners.forEach { it() } }

    /** The system task an app's tile stands for (Shizuku mode), for its snapshot. */
    private var taskIds: Map<String, Int> = emptyMap()
    fun taskIdFor(e: AppEntry): Int? = taskIds[e.key]

    /** What pulling down at the top of a view does: [PULL_SEARCH], [PULL_QUICK_SETTINGS] or [PULL_OFF]. */
    val pullDown: String get() = prefs.getString("pull_down", PULL_SEARCH)!!
    fun setPullDown(v: String) { prefs.edit().putString("pull_down", v).apply() }
    const val PULL_SEARCH = "search"
    const val PULL_QUICK_SETTINGS = "quick_settings"
    const val PULL_OFF = "off"

    /** Open apps keeps its two-column large tiles up to this many apps; more go three to a row. */
    val switcherLargeMax: Int get() = prefs.getInt("switcher_large", 10)
    fun setSwitcherLargeMax(n: Int) { prefs.edit().putInt("switcher_large", n).putInt("layout_version", layoutVersion + 1).apply() }

    /** Show Android's gesture pill on home (hidden: swipe up from the bottom edge still works). */
    val showGesturePill: Boolean get() = prefs.getBoolean("show_gesture_pill", true)
    fun setShowGesturePill(on: Boolean) { prefs.edit().putBoolean("show_gesture_pill", on).putInt("layout_version", layoutVersion + 1).apply() }

    /** Use the system Recents through Shizuku when it's allowed (switch in Customize Recents). */
    val matchRecents: Boolean get() = prefs.getBoolean("match_recents", true)
    fun setMatchRecents(on: Boolean) { prefs.edit().putBoolean("match_recents", on).apply(); refreshSystemRecents() }
    private fun useSystem() = matchRecents && SystemRecents.granted()

    /** The gesture pill sits on a black bar (else it floats over home). */
    val blackNavBar: Boolean get() = prefs.getBoolean("black_nav_bar", true)
    fun setBlackNavBar(on: Boolean) { prefs.edit().putBoolean("black_nav_bar", on).apply() }

    /** The thin position indicator while a view scrolls. */
    val showScrollbars: Boolean get() = prefs.getBoolean("show_scrollbars", true)
    fun setShowScrollbars(on: Boolean) { prefs.edit().putBoolean("show_scrollbars", on).apply() }

    /** Android's own status bar instead of the N9 one. */
    val systemStatusBar: Boolean get() = prefs.getBoolean("system_status_bar", false)
    fun setSystemStatusBar(on: Boolean) { prefs.edit().putBoolean("system_status_bar", on).putInt("layout_version", layoutVersion + 1).apply() }
    /** Bumped when a setting needs the home screen rebuilt. */
    val layoutVersion: Int get() = prefs.getInt("layout_version", 0)

    // ---- hidden apps ----

    val hidden: Set<String> get() = prefs.getStringSet("hidden", emptySet())!!
    fun setHidden(keys: Set<String>) { prefs.edit().putStringSet("hidden", HashSet(keys)).apply(); reload() }

    /** Every launchable app, hidden ones included (for settings and the switcher). */
    var installed: List<AppEntry> = emptyList()
        private set

    // ---- icon shapes ----

    /**
     * Every blanco app icon (icon-l-*) shares one silhouette: a 78x78 squircle on the 80x80 canvas,
     * same corner curve on all of them. Its alpha channel (taken from icon-l-folder) is the mask.
     */
    private val n9Mask: Bitmap by lazy { N9.bitmap(app, R.drawable.n9_app_folder) }

    /** The chosen shape as an alpha mask, sized like the N9 tile (78/80 of the icon box). */
    private fun shapeMask(shape: String, size: Int): Bitmap {
        if (shape == SHAPE_N9) return Bitmap.createScaledBitmap(n9Mask, size, size, true)
        val m = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(m)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val i = size / 80f
        val r = android.graphics.RectF(i, i, size - i, size - i)
        when (shape) {
            SHAPE_CIRCLE -> c.drawOval(r, p)
            SHAPE_ROUNDED -> c.drawRoundRect(r, r.width() * 0.22f, r.width() * 0.22f, p)
            SHAPE_SQUARE -> c.drawRoundRect(r, r.width() * 0.06f, r.width() * 0.06f, p)
            SHAPE_TEARDROP -> {
                val rr = r.width() / 2f
                val path = android.graphics.Path().apply {
                    addRoundRect(r, floatArrayOf(rr, rr, rr, rr, r.width() * 0.12f, r.width() * 0.12f, rr, rr), android.graphics.Path.Direction.CW)
                }
                c.drawPath(path, p)
            }
            else -> c.drawRect(r, p)
        }
        return m
    }

    private fun render(d: android.graphics.drawable.Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size); d.draw(Canvas(b)); return b
    }

    private fun applyMask(src: Bitmap, mask: Bitmap): Bitmap {
        val size = src.width
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        oc.drawBitmap(mask, null, Rect(0, 0, size, size), p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        oc.drawBitmap(src, 0f, 0f, p)
        return out
    }

    /** The icon drawn full-bleed into the tile, ready to be cut to a shape. */
    private fun fill(d: android.graphics.drawable.Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val tile = size * 78f / 80f
        if (d is AdaptiveIconDrawable) {
            // Adaptive layers are 108 units with the inner 72 guaranteed visible: map 72 → the N9 tile.
            val full = tile * 108f / 72f
            val off = ((size - full) / 2f).toInt()
            val end = (off + full).toInt()
            d.background?.let { it.setBounds(off, off, end, end); it.draw(c) }
            d.foreground?.let { it.setBounds(off, off, end, end); it.draw(c) }
        } else {
            // Legacy icon: if it already fills its square, use it as the tile; otherwise put it on a
            // white tile the way Android wraps legacy icons.
            val probe = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size); d.draw(Canvas(probe))
            val m = (size * 0.1f).toInt()
            val full = listOf(probe.getPixel(m, m), probe.getPixel(size - 1 - m, m), probe.getPixel(m, size - 1 - m), probe.getPixel(size - 1 - m, size - 1 - m))
                .all { Color.alpha(it) > 220 }
            if (full) c.drawBitmap(probe, 0f, 0f, null)
            else {
                c.drawColor(Color.WHITE)
                val inner = (tile * 0.72f).toInt()
                val o = (size - inner) / 2
                d.setBounds(o, o, o + inner, o + inner); d.draw(c)
            }
        }
        return b
    }

    private fun original(d: android.graphics.drawable.Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // N9 icon art leaves ~1-4px of air around the tile; inset adaptive icons to match.
        val inset = if (d is AdaptiveIconDrawable) N9.ipx(4) else 0
        d.setBounds(inset, inset, size - inset, size - inset)
        d.draw(Canvas(b))
        return b
    }

    private fun averageColor(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = s.getPixel(x, y)
            val a = Color.alpha(p)
            if (a < 128) continue
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
        }
        if (n == 0L) return Color.DKGRAY
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    // ---- launcher order ----

    /** Launcher items in order: apps and folders. [all] is the same apps flattened. */
    var items: List<LItem> = emptyList()
        private set

    private fun sortByOrder(list: List<AppEntry>): List<AppEntry> {
        val byKey = list.associateBy { it.key }
        val used = HashSet<String>()
        // Folders: {"<id>": {"name": "...", "apps": ["key", ...]}}
        val folders = LinkedHashMap<String, Folder>()
        runCatching {
            val o = org.json.JSONObject(prefs.getString("folders", "{}")!!)
            for (id in o.keys()) {
                val f = o.getJSONObject(id)
                val arr = f.optJSONArray("apps") ?: org.json.JSONArray()
                val apps = (0 until arr.length()).mapNotNull { byKey[arr.getString(it)] }.filter { used.add(it.key) }
                folders[id] = Folder(id, f.optString("name", "Folder"), apps.toMutableList())
            }
        }
        val out = ArrayList<LItem>()
        val saved = prefs.getString("order2", null)?.split('\n')?.filter { it.isNotEmpty() }
        if (saved != null) {
            for (l in saved) when {
                l.startsWith("f:") -> folders.remove(l.substring(2))?.let { out += LItem.Dir(it) }
                l.startsWith("a:") -> byKey[l.substring(2)]?.takeIf { used.add(it.key) }?.let { out += LItem.App(it) }
            }
            folders.values.forEach { out += LItem.Dir(it) }
        } else {
            val old = prefs.getString("order", null)?.split('\n')?.filter { it.isNotEmpty() }
            if (old != null) for (k in old) byKey[k]?.takeIf { used.add(it.key) }?.let { out += LItem.App(it) }
            else {
                val roleIdx = DEFAULT_ORDER.withIndex().associate { it.value to it.index }
                fun rank(e: AppEntry) = roleIdx[e.role ?: roleOf(e.pkg) ?: ""] ?: Int.MAX_VALUE
                list.sortedWith(compareBy<AppEntry> { rank(it) }.thenBy { it.label.lowercase() })
                    .forEach { used.add(it.key); out += LItem.App(it) }
            }
        }
        // Newly installed apps go to the end, like on the N9.
        list.filter { it.key !in used }.sortedBy { it.label.lowercase() }.forEach { out += LItem.App(it) }
        setItems(out)
        return all
    }

    /** Store a new launcher arrangement (apps and folders). Doesn't notify: the grid made the change. */
    fun setItems(l: List<LItem>) {
        items = l.toList()
        all = items.flatMap { when (it) { is LItem.App -> listOf(it.e); is LItem.Dir -> it.f.apps } }
        val folders = org.json.JSONObject()
        for (i in items) if (i is LItem.Dir) folders.put(i.f.id, org.json.JSONObject().apply {
            put("name", i.f.name)
            put("apps", org.json.JSONArray(i.f.apps.map { it.key }))
        })
        prefs.edit()
            .putString("order2", items.joinToString("\n") { when (it) { is LItem.App -> "a:" + it.e.key; is LItem.Dir -> "f:" + it.f.id } })
            .putString("folders", folders.toString())
            .apply()
    }

    fun newFolder(): Folder = Folder("f" + System.currentTimeMillis().toString(36), "Folder", mutableListOf())

    /** Start [e]. [bounds] is the icon/tile on screen, [opts] the window animation (see LaunchAnimator). */
    fun launch(e: AppEntry, bounds: Rect?, opts: android.os.Bundle?) {
        if (e.pkg == Demo.PKG) return // a demo grid's stand-in: nothing to open
        runCatching { launcherApps.startMainActivity(e.component, e.user, bounds, opts) }
        noteOpened(e)
    }

    /** The app used most recently (launched from home, or seen in usage stats). */
    fun lastUsed(): AppEntry? {
        val byKey = installed.associateBy { it.key }
        return recents.filter { byKey.containsKey(it.key) }.maxByOrNull { it.time }?.let { byKey[it.key] }
    }

    fun uninstall(e: AppEntry) {
        val i = Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", e.pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(i) }
    }

    // ---- recents (switcher) ----

    class Recent(val key: String, var time: Long)

    private var recents: MutableList<Recent> = loadRecents()
    private const val MAX_RECENTS = 12

    private fun loadRecents(): MutableList<Recent> {
        if (!::prefs.isInitialized) return mutableListOf()
        return prefs.getString("recents", "")!!.split('\n').filter { it.contains('|') }.map {
            val (k, t) = it.split('|'); Recent(k, t.toLong())
        }.toMutableList()
    }

    private fun saveRecents() {
        prefs.edit().putString("recents", recents.joinToString("\n") { "${it.key}|${it.time}" }).apply()
    }

    /** Recently opened apps that are still installed, newest window first. */
    fun recentApps(): List<AppEntry> {
        Demo.recents()?.let { return it }
        systemTasks?.let { tasks ->
            // Shizuku mode: exactly the system Recents list, in its order.
            val byKey = installed.associateBy { it.key }
            val me = Process.myUserHandle()
            val now = System.currentTimeMillis()
            recentlyClosed.entries.removeAll { now - it.value > CLOSE_GRACE_MS }
            val ids = HashMap<String, Int>()
            return tasks.filter { it.component.packageName !in recentlyClosed }.mapNotNull { t ->
                (byKey[keyOf(t.component, me)] ?: installed.firstOrNull { it.pkg == t.component.packageName && it.user == me })
                    ?.also { e -> if (e.key !in ids) ids[e.key] = t.id }
            }.distinctBy { it.key }.also { taskIds = ids }
        }
        if (recents.isEmpty() && prefs.contains("recents")) recents = loadRecents()
        val byKey = installed.associateBy { it.key }
        return recents.mapNotNull { byKey[it.key] }
    }

    // ---- system Recents mirroring (Shizuku) ----

    /** The system's recent tasks when Shizuku access is available, else null (own tracking is used). */
    private var systemTasks: List<SystemRecents.Task>? = null

    /**
     * Re-read the system Recents list now (blocking, a quick binder call). Used when leaving home, so
     * the app just opened is already first in the switcher before its close animation is aimed at it.
     */
    fun refreshSystemRecentsNow() {
        if (!useSystem()) return
        val t = SystemRecents.tasks() ?: return
        val old = systemTasks?.map { it.id }
        systemTasks = t
        if (old != t.map { it.id }) recentListeners.forEach { it() }
    }

    /** Re-read the system Recents list (in the background), then refresh the switcher. */
    fun refreshSystemRecents() {
        if (!useSystem()) {
            if (systemTasks != null) { systemTasks = null; recentListeners.forEach { it() } }
            return
        }
        usageThread.execute {
            val t = SystemRecents.tasks()
            main.post {
                val old = systemTasks?.map { it.id }
                systemTasks = t
                if (old != t?.map { it.id }) recentListeners.forEach { it() }
            }
        }
    }

    fun noteOpened(e: AppEntry, time: Long = System.currentTimeMillis()) {
        recentlyClosed.remove(e.pkg)
        val existing = recents.firstOrNull { it.key == e.key }
        if (existing != null) { existing.time = time; saveRecents(); return }
        else {
            // N9: a new window enters at the start of the switcher; existing ones keep their place.
            recents.add(0, Recent(e.key, time))
            while (recents.size > MAX_RECENTS) recents.removeAt(recents.size - 1)
        }
        saveRecents()
        recentListeners.forEach { it() }
    }

    /**
     * Apps just closed here, and when. A Recents list read before the system finished removing them
     * would otherwise put them back for a moment (cutting their close animation short).
     */
    private val recentlyClosed = HashMap<String, Long>()
    private const val CLOSE_GRACE_MS = 4000L

    fun closeRecent(e: AppEntry) {
        recentlyClosed[e.pkg] = System.currentTimeMillis()
        systemTasks?.let { tasks ->
            // Shizuku mode: remove the app's tasks from the system Recents too.
            val ids = tasks.filter { it.component.packageName == e.pkg }.map { it.id }
            systemTasks = tasks.filter { it.id !in ids }
            usageThread.execute { ids.forEach { SystemRecents.remove(it) }; main.post { refreshSystemRecents() } }
        }
        recents.removeAll { it.key == e.key }
        prefs.edit().putLong("closed:" + e.key, System.currentTimeMillis()).apply()
        saveRecents()
        runCatching { app.getSystemService(android.app.ActivityManager::class.java).killBackgroundProcesses(e.pkg) }
    }

    private fun removeRecent(pkg: String) {
        if (recents.removeAll { it.key.startsWith("$pkg/") }) saveRecents()
    }

    fun hasUsageAccess(): Boolean {
        val ops = app.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Pull apps the user brought to the foreground outside this launcher (notifications, recents key,
     * other shortcuts) from UsageStats, if access was granted.
     */
    private val usageThread = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Reading usage events can take tens of ms: do it off the main thread, apply on it. */
    fun syncRecentsFromUsage() {
        if (!hasUsageAccess()) return
        val usm = app.getSystemService(UsageStatsManager::class.java) ?: return
        usageThread.execute {
            val now = System.currentTimeMillis()
            val events = runCatching { usm.queryEvents(now - 6 * 3600_000L, now) }.getOrNull() ?: return@execute
            val ev = UsageEvents.Event()
            val lastFg = LinkedHashMap<String, Long>()
            val lastGone = HashMap<String, Long>()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                when (ev.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> lastFg[ev.packageName] = ev.timeStamp
                    ACTIVITY_DESTROYED -> lastGone[ev.packageName] = ev.timeStamp
                }
            }
            main.post { applyUsage(lastFg, lastGone) }
        }
    }

    /** UsageEvents.Event.ACTIVITY_DESTROYED (API 29): reported when a task is swiped away in Recents. */
    private const val ACTIVITY_DESTROYED = 24

    private fun applyUsage(lastFg: Map<String, Long>, lastGone: Map<String, Long>) {
        val me = Process.myUserHandle()
        var changed = false
        // Closed in the system Recents (its activity was destroyed after it was last used): drop it here too.
        for ((pkg, gone) in lastGone) {
            val fg = lastFg[pkg] ?: 0L
            if (gone <= fg) continue
            val removed = recents.removeAll { r -> r.key.startsWith("$pkg/") && r.time < gone }
            if (removed) changed = true
        }
        for ((pkg, t) in lastFg.entries.sortedBy { it.value }) {
            if (pkg == app.packageName) continue
            if ((lastGone[pkg] ?: 0L) > t) continue // closed since
            val e = installed.firstOrNull { it.pkg == pkg && it.user == me } ?: continue
            val closedAt = prefs.getLong("closed:" + e.key, 0L)
            if (t <= closedAt) continue
            val r = recents.firstOrNull { it.key == e.key }
            if (r == null || r.time < t) {
                if (r == null) { recents.add(0, Recent(e.key, t)); while (recents.size > MAX_RECENTS) recents.removeAt(recents.size - 1) }
                else r.time = t
                changed = true
            }
        }
        if (changed) { saveRecents(); recentListeners.forEach { it() } }
    }

    fun iconPaint() = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
}
