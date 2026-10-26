package com.amiigo.harmattan

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

/**
 * Made-up notifications for screenshots and clips (debug builds only), shown in Today instead of the
 * real ones so nothing personal ends up in them. Turn on and off with:
 *   adb shell am start -n com.amiigo.harmattan/.HomeActivity --ez demo true|false
 */
object Demo {
    var items: List<EventItem>? = null
        private set
    var feed: List<EventItem>? = null
        private set

    fun handle(ctx: Context, intent: android.content.Intent?) {
        if (intent == null || !intent.hasExtra("demo")) return
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val on = intent.getBooleanExtra("demo", false)
        items = if (on) build(ctx) else null
        folder = null
        recentList = null
        Weather.demoChanged()
        feed = if (on) buildFeed(ctx) else null
        EventStore.demoChanged()
        (ctx as? HomeActivity)?.demoChanged()
    }

    const val PKG = "demo.n9"

    /**
     * The demo grid: the N9's own apps in its launcher order (the phone's app for a role when it has
     * one, else a stand-in with the N9 icon), and a folder of the N9's extras. Nothing from the phone.
     */
    fun apps(all: List<LItem>): List<LItem> {
        if (items == null) return all
        val real = all.filterIsInstance<LItem.App>().filter { it.e.role != null }.associateBy { it.e.role }
        val out = ArrayList<LItem>()
        for (role in Apps.DEFAULT_ORDER) {
            val name = mapOf("documents" to "Documents", "rss" to "Feeds")[role]
            (real[role] ?: Apps.demoEntry(role, name = name)?.let { LItem.App(it) })?.let { out += it }
        }
        val extras = listOfNotNull(
            Apps.demoEntry("drive", R.drawable.n9_app_drive, "Drive"),
            Apps.demoEntry("firefox"),
            Apps.demoEntry("guide", R.drawable.n9_app_user_guide, "User guide"),
            Apps.demoEntry("accounts", R.drawable.n9_app_accounts, "Accounts"),
        )
        out += LItem.Dir(folder ?: Folder("demo", "Extras", extras.toMutableList()).also { folder = it })
        return out
    }
    private var folder: Folder? = null

    /** Recents in demo mode: N9 apps on their app-coloured cards (as without Shizuku). */
    fun recents(): List<AppEntry>? {
        if (items == null) return null
        return recentList ?: listOf("music", "maps", "conversation", "calendar", "gallery", "browser")
            .mapNotNull { Apps.demoEntry(it) }.also { recentList = it }
    }
    private var recentList: List<AppEntry>? = null

    /** A folder's apps in demo mode: without app-patching tools. */
    fun folderApps(apps: List<AppEntry>): List<AppEntry> = apps

    fun dismiss(key: String) { items = items?.filter { it.key != key } }

    private fun build(ctx: Context): List<EventItem> {
        val now = System.currentTimeMillis()
        val min = 60_000L
        fun icon(vararg pkgs: String) = pkgs.firstNotNullOfOrNull { p -> Apps.installed.firstOrNull { it.pkg == p } }
        fun person(name: String, c1: Int, c2: Int, pkg: String) =
            EventStore.withBadge(EventStore.masked(avatar(name, c1, c2)), EventStore.appBadge(pkg) ?: icon(pkg)?.icon)
        val out = ArrayList<EventItem>()
        fun add(key: String, pkg: String, title: String, sub: String, ago: Long, ic: Bitmap?, clearable: Boolean = true) {
            out += EventItem("demo:$key", pkg, title, null, sub, now - ago * min, ic, null, null, null, sub, clearable)
        }
        val messages = icon("com.google.android.apps.messaging", "com.samsung.android.messaging")?.pkg ?: "com.google.android.apps.messaging"
        add("m1", messages, "Sauna at seven? Bring the good towels", "Aino", 2, person("Aino", 0xFFE8A87C.toInt(), 0xFFC38D9E.toInt(), messages))
        icon("com.facebook.orca", "com.discord", "com.whatsapp")?.let {
            add("m2", it.pkg, "Found the old N9 in a drawer. Still boots!", "Mikko", 9, person("Mikko", 0xFF41B3A3.toInt(), 0xFF2E86AB.toInt(), it.pkg))
        }
        icon("com.samsung.android.calendar", "com.google.android.calendar")?.let {
            add("c", it.pkg, "Design review", "Today 14:00 · Studio 2", 25, EventStore.masked(it.icon))
        }
        icon("com.google.android.gm")?.let {
            add("g", it.pkg, "Your boarding pass for Helsinki", "Finnair", 48, EventStore.masked(it.icon))
        }
        icon("com.apple.android.music", "com.spotify.music", "com.google.android.apps.youtube.music")?.let {
            add("music", it.pkg, "Northern Lights", "The Snowfields", 3,
                EventStore.withBadge(EventStore.roundedSquare(art(), N9.ipx(64)), EventStore.appBadge(it.pkg) ?: it.icon), clearable = false)
        }
        return out.sortedByDescending { it.time }
    }

    private fun buildFeed(ctx: Context): List<EventItem> {
        val now = System.currentTimeMillis()
        val min = 60_000L
        fun pkg(vararg p: String) = p.firstOrNull { x -> Apps.installed.any { it.pkg == x } }
        fun pic(name: String, c1: Int, c2: Int, app: String?) =
            EventStore.withBadge(EventStore.masked(avatar(name, c1, c2)), app?.let { EventStore.appBadge(it) })
        val out = ArrayList<EventItem>()
        fun post(key: String, title: String, body: String?, who: String, source: String, ago: Long, icon: Bitmap?, image: Bitmap? = null) {
            out += EventItem("demof:$key", "demo", title, body, who, now - ago * min, icon, image, null, null, source, false)
        }
        val yt = pkg("app.morphe.android.youtube", "app.revanced.android.youtube", "com.google.android.youtube")
        post("yt", "MeeGo Harmattan, ten years on: the phone that got swiping right", "Retro Tech Lab", "Retro Tech Lab", "YouTube", 14,
            pic("Retro Tech Lab", 0xFFFF6B6B.toInt(), 0xFFC44569.toInt(), yt), scene(336, 189, 0))
        val rd = pkg("com.reddit.frontpage")
        post("rd", "Found my N9 in a drawer and it still boots", "u/polar_swipe",
            "u/polar_swipe", "Nokia", 37, pic("Nokia", 0xFF4E7AC7.toInt(), 0xFF1B3A6B.toInt(), rd))
        val ig = pkg("com.instagram.android")
        post("ig", "Northern lights over the lake last night", "No filter, just patience and a very cold tripod.",
            "aino.photos", "Instagram", 63, pic("Aino", 0xFFE8A87C.toInt(), 0xFFC38D9E.toInt(), ig), scene(336, 336, 1))
        post("rss", "The making of Nokia Pure", "How a typeface built for small screens became a whole brand's voice.",
            "Type Weekly", "RSS", 180, EventStore.masked(Bitmap.createScaledBitmap(N9.bitmap(ctx, R.drawable.n9_app_rss), N9.ipx(64), N9.ipx(64), true)))
        return out
    }

    /** A post picture stand-in: night sky, aurora and hills; [v] 1 adds a lake reflection. */
    private fun scene(w: Int, h: Int, v: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val W = w.toFloat(); val H = h.toFloat()
        c.drawRect(0f, 0f, W, H, Paint().apply { shader = LinearGradient(0f, 0f, 0f, H, 0xFF081428.toInt(), 0xFF173B4F.toInt(), Shader.TileMode.CLAMP) })
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, H * 0.1f, 0f, H * 0.65f, 0xCC6CFFB0.toInt(), 0x006CFFB0, Shader.TileMode.CLAMP) }
        c.drawPath(android.graphics.Path().apply {
            moveTo(0f, H * 0.4f); cubicTo(W * 0.25f, H * 0.05f, W * 0.55f, H * 0.45f, W, H * 0.15f); lineTo(W, H * 0.6f); lineTo(0f, H * 0.6f); close()
        }, glow)
        val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
        val r = java.util.Random(7)
        repeat(40) { c.drawCircle(r.nextFloat() * W, r.nextFloat() * H * 0.45f, 0.6f + r.nextFloat(), star) }
        val hill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF040A12.toInt() }
        val base = if (v == 1) H * 0.62f else H * 0.78f
        c.drawPath(android.graphics.Path().apply { moveTo(0f, base); quadTo(W * 0.3f, base - H * 0.18f, W * 0.6f, base - H * 0.04f); quadTo(W * 0.8f, base - H * 0.12f, W, base); lineTo(W, H); lineTo(0f, H); close() }, hill)
        if (v == 1) c.drawRect(0f, base + H * 0.04f, W, H, Paint().apply { shader = LinearGradient(0f, base, 0f, H, 0x553C9D8A, 0x10040A12, Shader.TileMode.CLAMP) })
        return b
    }

    /** A contact picture stand-in: the initial on a soft two-colour gradient. */
    private fun avatar(name: String, c1: Int, c2: Int): Bitmap {
        val s = N9.ipx(64)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(), c1, c2, Shader.TileMode.CLAMP) })
        val p = N9.textPaint(N9.light, 34f, android.graphics.Color.WHITE, scaled = false)
        val fm = p.fontMetrics
        val l = name.take(1)
        c.drawText(l, (s - p.measureText(l)) / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, p)
        return b
    }

    /** Album art stand-in: a night sky with an aurora. */
    private fun art(): Bitmap {
        val s = N9.ipx(64)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val f = s.toFloat()
        c.drawRect(0f, 0f, f, f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, f, 0xFF0B1D3A.toInt(), 0xFF1F4E5F.toInt(), Shader.TileMode.CLAMP) })
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, f * 0.2f, 0f, f * 0.7f, 0xAA7CFFB2.toInt(), 0x007CFFB2, Shader.TileMode.CLAMP)
        }
        val path = android.graphics.Path().apply {
            moveTo(0f, f * 0.45f); cubicTo(f * 0.3f, f * 0.15f, f * 0.6f, f * 0.5f, f, f * 0.25f)
            lineTo(f, f * 0.7f); lineTo(0f, f * 0.7f); close()
        }
        c.drawPath(path, glow)
        val hill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050B14.toInt() }
        c.drawPath(android.graphics.Path().apply { moveTo(0f, f * 0.8f); quadTo(f * 0.4f, f * 0.62f, f, f * 0.78f); lineTo(f, f); lineTo(0f, f); close() }, hill)
        return b
    }
}
