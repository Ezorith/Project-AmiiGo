package com.amiigo.harmattan

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** One row in the events view: a notification banner or a feed item. */
class EventItem(
    val key: String,
    val pkg: String,
    val title: String,
    val body: String?,
    val subtitle: String?,
    val time: Long,
    val icon: Bitmap?,       // 64px N9 icon / masked avatar
    val image: Bitmap?,      // feed picture
    val intent: PendingIntent?,
    val link: String?,
    val source: String?,
    val clearable: Boolean,
    /** The feed address an RSS post was read from (so removing that source removes its posts). */
    val origin: String? = null,
)

/**
 * Events model. Notifications come from [EventsListenerService]. The feed is persistent:
 * posts from social apps are kept after their notifications are gone (the N9 feed kept
 * Facebook/Twitter/RSS updates), plus RSS/Atom sources configured in Home settings.
 */
object EventStore {
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<() -> Unit>()

    var service: EventsListenerService? = null
    /** What Today shows: the made-up ones while [Demo] is on, else the real notifications. */
    val notifications: List<EventItem> get() = Demo.items ?: real
    private var real: List<EventItem> = emptyList()
    val feed: List<EventItem> get() = Demo.feed ?: realFeed
    private var realFeed: List<EventItem> = emptyList()
    var unseen = false
        private set

    private const val MAX_FEED = 100
    /** SwipeEventFeedAreaStyle auto-update-default-interval: 60 (minutes). */
    const val AUTO_UPDATE_MS = 60 * 60_000L
    private const val RETRY_MS = 2 * 60_000L

    /** Known social apps: their posts go to the feed rather than to notifications, unless changed in settings. */
    val SOCIAL = setOf(
        "com.facebook.katana", "com.facebook.lite", "com.instagram.android", "com.instagram.barcelona",
        "com.twitter.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.reddit.frontpage",
        "com.linkedin.android", "org.joinmastodon.android", "org.joinmastodon.android.moshidon", "com.keylesspalace.tusky",
        "xyz.blueskyweb.app", "com.pinterest", "com.tumblr", "com.google.android.youtube", "com.snapchat.android",
        "com.vkontakte.android", "com.flickr.android", "com.discord", "com.bereal.ft", "com.strava",
    )

    /** Show ongoing and system notifications (charging, USB options, private tabs…) too. */
    val showOngoing: Boolean get() = prefs.getBoolean("show_ongoing", true)
    fun setShowOngoing(on: Boolean) {
        prefs.edit().putBoolean("show_ongoing", on).apply()
        refresh()
    }

    /** Apps whose notifications become feed posts (Home settings → Feed → Apps in the feed). */
    val feedApps: Set<String> get() = prefs.getStringSet("feed_apps", null) ?: SOCIAL
    fun setFeedApps(s: Set<String>) {
        prefs.edit().putStringSet("feed_apps", HashSet(s)).apply()
        prune()
        refresh()
    }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("events", Context.MODE_PRIVATE)
        MediaWatcher.addListener { service?.let { s -> runCatching { setActive(s.activeNotifications ?: emptyArray()) } } }
        realFeed = loadFeed()
        // Posts saved before they remembered their source are pruned below; fetch fresh ones soon.
        if (!prefs.getBoolean("origins_v2", false)) prefs.edit().putBoolean("origins_v2", true).putLong("last_update", 0L).apply()
        prune()
    }

    /**
     * Drop feed posts whose source is gone: RSS posts from a removed feed address (or saved before
     * posts remembered their source), and posts from apps no longer chosen for the feed.
     */
    private fun prune() {
        val urls = rssSources.toSet()
        val apps = feedApps
        val kept = realFeed.filter { i ->
            if (i.key.startsWith("rss:")) i.origin != null && i.origin in urls
            else i.pkg.isEmpty() || i.pkg in apps || (!prefs.contains("feed_apps"))
        }
        if (kept.size != realFeed.size) {
            realFeed = kept
            changed()
            io.execute { saveFeed(kept) }
        }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }
    fun demoChanged() = changed()

    fun markSeen() { if (unseen) { unseen = false; changed() } }

    // ---- notifications ----

    fun setActive(list: Array<StatusBarNotification>) {
        val items = ArrayList<EventItem>()
        val feedItems = ArrayList<EventItem>()
        for (sbn in list) {
            if (isFeed(sbn)) feedItem(sbn)?.let { feedItems += it } else toItem(sbn)?.let { items += it }
        }
        real = items.sortedByDescending { it.time }
        if (feedItems.isNotEmpty()) mergeFeed(feedItems) else changed()
    }

    fun onPosted(sbn: StatusBarNotification) {
        if (isFeed(sbn)) {
            feedItem(sbn)?.let { unseen = true; mergeFeed(listOf(it)) }
            return
        }
        val item = toItem(sbn) ?: return
        real = (listOf(item) + real.filter { it.key != item.key }).sortedByDescending { it.time }
        unseen = true
        changed()
    }

    fun onRemoved(sbn: StatusBarNotification) {
        val before = notifications.size
        real = real.filter { it.key != sbn.key }
        if (notifications.size != before) changed()
    }

    fun clearNotifications() {
        // Demo mode: only the made-up ones go; the real notifications are left alone.
        if (Demo.items != null) { Demo.items?.filter { it.clearable }?.forEach { Demo.dismiss(it.key) }; changed(); return }
        runCatching { service?.cancelAllNotifications() }
        real = real.filter { !it.clearable }
        changed()
    }

    /** Dismiss one notification (its ✕ while Today is held), here and in the system shade. */
    fun dismiss(item: EventItem) {
        if (!item.clearable) return
        if (item.key.startsWith("demo:")) { Demo.dismiss(item.key); changed(); return }
        runCatching { service?.cancelNotification(item.key) }
        real = real.filter { it.key != item.key }
        changed()
    }

    fun open(item: EventItem) {
        val pi = item.intent
        val link = item.link
        when {
            pi != null && sendPending(app, pi) -> {}
            !link.isNullOrEmpty() -> openLink(link, item.pkg)
            else -> app.packageManager.getLaunchIntentForPackage(item.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { runCatching { app.startActivity(it) } }
        }
        // Once opened, a notification goes away, here and in the system shade (ongoing ones stay).
        if (item.clearable && notifications.any { it.key == item.key }) {
            runCatching { service?.cancelNotification(item.key) }
            real = real.filter { it.key != item.key }
            changed()
        }
    }

    /**
     * Send another app's PendingIntent (a notification's tap action) as the visible home screen.
     * Since Android 14 the sender has to say it lends its right to open screens; without that, the
     * app the notification belongs to (in the background) isn't allowed to come forward.
     */
    fun sendPending(ctx: Context, pi: PendingIntent): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 34) {
            val o = android.app.ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(ctx, 0, null, null, null, null, o.toBundle())
        } else pi.send()
    }.isSuccess

    /**
     * A post's link in its own app when that's installed (Reddit, YouTube, Bluesky…), else in any
     * other app that handles it, else on the website in the browser.
     */
    private fun openLink(link: String, pkg: String) {
        val uri = Uri.parse(link)
        val pm = app.packageManager
        fun start(i: Intent) = runCatching { app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        // Apps on this phone that open this kind of link, without the browsers (which open any link):
        // that also finds unofficial clients of a service (a patched YouTube, a Reddit reader).
        val browsers = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()
        val handlers = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, uri), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.filter { it !in browsers && it != app.packageName }
        val known = (Rss.appsForLink(link) + pkg).filter { it.isNotEmpty() }
        val order = (known.filter { it in handlers } + handlers + known).distinct()
            .filter { p -> Apps.installed.any { it.pkg == p } }
        for (p in order) {
            val i = Intent(Intent.ACTION_VIEW, uri).setPackage(p)
            if (pm.resolveActivity(i, 0) != null && start(i)) return
        }
        if (Build.VERSION.SDK_INT >= 30 && start(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER))) return
        start(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun isFeed(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return sbn.packageName in feedApps || (n.category == Notification.CATEGORY_SOCIAL && !prefs.contains("feed_apps"))
    }

    private fun skip(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == app.packageName) return true
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        // Media players are shown by the Now Playing row when that's on; otherwise as notifications.
        if (SHOW_NOW_PLAYING && (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || n.category == Notification.CATEGORY_TRANSPORT)) return true
        // Ongoing ones (charging, USB, private tabs, downloads) only when chosen in settings.
        if (sbn.isOngoing && n.category != Notification.CATEGORY_CALL && !showOngoing) return true
        if (n.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() &&
            n.extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()) return true
        // System placeholders that are only an internal name, like Samsung's "MediaOngoingActivity".
        if (n.extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank() &&
            Regex("^[A-Z][a-z]+([A-Z][a-z]+){2,}$").matches(n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())) return true
        return false
    }

    private fun texts(n: Notification): Pair<String, String?> {
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()
        // MessagingStyle: use the latest message.
        @Suppress("DEPRECATION")
        val msgs = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            (msgs.last() as? Bundle)?.getCharSequence("text")?.let { text = it.toString() }
        }
        return title to text?.takeIf { it.isNotEmpty() }
    }

    private fun toItem(sbn: StatusBarNotification): EventItem? {
        if (skip(sbn)) return null
        val n = sbn.notification
        val (title, text) = texts(n)
        // N9 banner: the message is the title line, who/what it's from is the subtitle.
        val mainText = text ?: title
        val sub = if (text != null) title.takeIf { it.isNotEmpty() } else null
        return EventItem(
            key = sbn.key, pkg = sbn.packageName, title = mainText, body = null, subtitle = sub,
            time = if (n.`when` > 0) n.`when` else sbn.postTime,
            icon = notificationIcon(sbn), image = null, intent = n.contentIntent, link = null,
            source = appLabel(sbn.packageName), clearable = sbn.isClearable,
        )
    }

    private fun appLabel(pkg: String): String? = runCatching {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    // ---- icons ----

    private val iconBg: Int by lazy {
        // The dark tile colour of the N9 "-inverse" content icons, sampled from the firmware art.
        N9.bitmap(app, R.drawable.n9_content_chat).getPixel(32 * N9.HD, 6 * N9.HD).let { if (Color.alpha(it) > 200) it else 0xFF3A3A3A.toInt() }
    }

    private fun notificationIcon(sbn: StatusBarNotification): Bitmap {
        val n = sbn.notification
        val size = N9.ipx(64)
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || n.category == Notification.CATEGORY_TRANSPORT) {
            // The player's own session has the real cover; the notification's large icon otherwise.
            val art = (if (MediaWatcher.pkg == sbn.packageName) MediaWatcher.art else null)
                ?: n.getLargeIcon()?.let { loadIcon(it, sbn.packageName) }?.let { drawableBitmap(it, size, size) }
            if (art != null) return withBadge(roundedSquare(art, size), appBadge(sbn.packageName))
        }
        // Messages: the sender's photo with the app as a badge, as feed posts show their source.
        senderPhoto(sbn, size)?.let { photo ->
            val appIcon = Apps.installed.firstOrNull { it.pkg == sbn.packageName }?.icon
            return withBadge(masked(photo), appBadge(sbn.packageName) ?: appIcon)
        }
        // The app's own icon, as the Applications view draws it (shape and icon style included).
        (Apps.installed.firstOrNull { it.pkg == sbn.packageName && it.user == sbn.user }
            ?: Apps.installed.firstOrNull { it.pkg == sbn.packageName })
            ?.let { return Bitmap.createScaledBitmap(it.icon, size, size, true) }
        // No launcher icon (system services): the app's icon from the system, if it has a real one.
        runCatching {
            val pm = app.packageManager
            val ai = pm.getApplicationInfo(sbn.packageName, 0)
            // "android" and System UI only have the stock robot; their own small icon says more.
            if (ai.icon != 0 && sbn.packageName != "android" && sbn.packageName != "com.android.systemui")
                return drawableBitmap(pm.getApplicationIcon(ai), size, size)
        }
        // Generic: the app's monochrome status icon, white on the N9 dark tile.
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = iconBg }
        c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), N9.px(8f), N9.px(8f), p)
        val d = loadIcon(n.smallIcon, sbn.packageName)
        if (d != null) {
            val g = N9.ipx(32)
            d.setBounds((size - g) / 2, (size - g) / 2, (size + g) / 2, (size + g) / 2)
            d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            d.draw(c)
        }
        return out
    }

    /** A message's sender photo (MessagingStyle's last sender, else the large icon), or null for other notifications. */
    private fun senderPhoto(sbn: StatusBarNotification, size: Int): Bitmap? = runCatching {
        val n = sbn.notification
        val e = n.extras
        @Suppress("DEPRECATION")
        val msgs = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        val isMessage = n.category == Notification.CATEGORY_MESSAGE || msgs != null ||
            e.getString(Notification.EXTRA_TEMPLATE)?.contains("MessagingStyle") == true
        if (!isMessage) return@runCatching null
        @Suppress("DEPRECATION")
        val sender = (msgs?.lastOrNull() as? android.os.Bundle)?.getParcelable<android.app.Person>("sender_person")?.icon
        val icon = sender ?: n.getLargeIcon() ?: return@runCatching null
        loadIcon(icon, sbn.packageName)?.let { drawableBitmap(it, size, size) }
    }.getOrNull()

    private fun loadIcon(icon: Icon?, pkg: String): Drawable? = runCatching {
        icon?.loadDrawable(app.createPackageContext(pkg, 0))
    }.getOrNull()

    /** [src] cropped square and rounded like the notification icon tiles (8 of 64 px). */
    fun roundedSquare(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val r = N9.px(8f) * size / N9.px(64f)
        c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val s = minOf(src.width, src.height)
        c.drawBitmap(src, Rect((src.width - s) / 2, (src.height - s) / 2, (src.width + s) / 2, (src.height + s) / 2), Rect(0, 0, size, size), p)
        return out
    }

    /** Avatar masked by meegotouch-home-event-icon-mask (SwipeEventFeedItemStyle icon-mask-image). */
    fun masked(src: Bitmap): Bitmap {
        val size = N9.ipx(64)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val s = minOf(src.width, src.height)
        val r = Rect((src.width - s) / 2, (src.height - s) / 2, (src.width + s) / 2, (src.height + s) / 2)
        c.drawBitmap(src, r, Rect(0, 0, size, size), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawBitmap(N9.bitmap(app, R.drawable.n9_event_icon_mask), null, Rect(0, 0, size, size), p)
        return out
    }

    /**
     * A feed avatar with where it came from as a small badge at its bottom right (Reddit, YouTube, the
     * app that posted it), set on a black ring so it reads against any picture.
     */
    fun withBadge(avatar: Bitmap, badge: Bitmap?): Bitmap {
        if (badge == null) return avatar
        val size = N9.ipx(64)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(avatar, null, Rect(0, 0, size, size), p)
        // The logo as it is (its own outline and transparency), fitted into the bottom-right corner.
        val logo = trimmed(badge)
        val box = N9.px(24f)
        val k = box / maxOf(logo.width, logo.height)
        val w = logo.width * k; val h = logo.height * k
        c.drawBitmap(logo, null, android.graphics.RectF(size - w, size - h, size.toFloat(), size.toFloat()), p)
        return out
    }

    /** [b] cropped to the part that isn't transparent. */
    private fun trimmed(b: Bitmap): Bitmap {
        var l = b.width; var t = b.height; var r = -1; var btm = -1
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        for (y in 0 until b.height) for (x in 0 until b.width) if ((px[y * b.width + x] ushr 24) > 16) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > btm) btm = y
        }
        if (r < 0 || (l == 0 && t == 0 && r == b.width - 1 && btm == b.height - 1)) return b
        return Bitmap.createBitmap(b, l, t, r - l + 1, btm - t + 1)
    }

    private val badges = HashMap<String, Bitmap?>()

    /**
     * An app's logo for a badge, in its own shape rather than the launcher's icon shape: an adaptive
     * icon's foreground layer when that's a coloured logo (YouTube's play button), otherwise the icon
     * as the system draws it (white glyphs need their background to be seen).
     */
    fun appBadge(pkg: String): Bitmap? = synchronized(badges) {
        if (badges.containsKey(pkg)) return badges[pkg]
        val made = runCatching {
            val d = app.packageManager.getApplicationIcon(pkg)
            val size = N9.ipx(96)
            val fg = (d as? android.graphics.drawable.AdaptiveIconDrawable)?.foreground
            val logo = fg?.let { drawableBitmap(it, size, size) }?.let { trimmed(it) }?.takeIf { !mostlyLight(it) }
            logo ?: trimmed(drawableBitmap(d, size, size))
        }.getOrNull()
        badges[pkg] = made
        made
    }

    /** Whether the drawn part of [b] is nearly all white (a glyph meant for a coloured background). */
    private fun mostlyLight(b: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(b, 24, 24, true)
        var n = 0; var light = 0
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = s.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            n++
            if (Color.red(p) > 215 && Color.green(p) > 215 && Color.blue(p) > 215) light++
        }
        return n == 0 || light > n * 0.8f
    }

    private fun drawableBitmap(d: Drawable, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, b.width, b.height); d.draw(Canvas(b)); return b
    }

    // ---- feed ----

    private fun feedItem(sbn: StatusBarNotification): EventItem? {
        if (skip(sbn)) return null
        val n = sbn.notification
        val (title, text) = texts(n)
        val e = n.extras
        val avatar = n.getLargeIcon()?.let { loadIcon(it, sbn.packageName) }?.let { drawableBitmap(it, N9.ipx(64), N9.ipx(64)) }
        @Suppress("DEPRECATION")
        var picture: Bitmap? = e.get(Notification.EXTRA_PICTURE) as? Bitmap
        if (picture == null && Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val ic = e.get(Notification.EXTRA_PICTURE_ICON) as? Icon
            picture = ic?.let { loadIcon(it, sbn.packageName) }?.let { d ->
                drawableBitmap(d, d.intrinsicWidth.coerceIn(1, 600), d.intrinsicHeight.coerceIn(1, 600))
            }
        }
        val appIcon = Apps.installed.firstOrNull { it.pkg == sbn.packageName }?.icon
        val time = if (n.`when` > 0) n.`when` else sbn.postTime
        return EventItem(
            key = "n:" + sbn.key + ":" + time,
            pkg = sbn.packageName, title = title.ifEmpty { appLabel(sbn.packageName).orEmpty() }, body = text, subtitle = null,
            // The sender's picture with the app's badge; just the app's icon when there's no picture.
            time = time, icon = if (avatar != null) withBadge(masked(avatar), appBadge(sbn.packageName) ?: appIcon) else appIcon?.let { masked(it) }, image = picture?.let { scaleDown(it, N9.ipx(168)) },
            intent = n.contentIntent, link = null, source = appLabel(sbn.packageName), clearable = true,
        )
    }

    fun mergeFeed(items: List<EventItem>) {
        val byKey = LinkedHashMap<String, EventItem>()
        // Older posts from the same source take its latest picture (a new icon, or the letter tile's new colour).
        val fresh = items.filter { it.origin != null && it.icon != null }.associate { (it.origin + "|" + it.source) to it.icon }
        for (i in realFeed) byKey[i.key] = fresh[i.origin + "|" + i.source]?.takeIf { i.origin != null && it !== i.icon }?.let { icon ->
            EventItem(i.key, i.pkg, i.title, i.body, i.subtitle, i.time, icon, i.image, i.intent, i.link, i.source, i.clearable, i.origin)
        } ?: i
        for (i in items) byKey[i.key] = i
        realFeed = byKey.values.sortedByDescending { it.time }.take(MAX_FEED)
        changed()
        val snapshot = realFeed
        io.execute { saveFeed(snapshot) }
    }

    fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val k = max.toFloat() / maxOf(b.width, b.height)
        if (k >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    private val feedDir get() = File(app.filesDir, "feed").apply { mkdirs() }

    private fun saveFeed(items: List<EventItem>) {
        val arr = JSONArray()
        val keepFiles = HashSet<String>()
        for (i in items) {
            val o = JSONObject()
            o.put("key", i.key); o.put("pkg", i.pkg); o.put("title", i.title); o.put("time", i.time)
            i.body?.let { o.put("body", it) }; i.link?.let { o.put("link", it) }; i.source?.let { o.put("source", it) }
            i.origin?.let { o.put("origin", it) }
            val h = Integer.toHexString(i.key.hashCode())
            i.icon?.let { val f = "a_$h.png"; writePng(f, it); o.put("icon", f); keepFiles += f }
            i.image?.let { val f = "i_$h.png"; writePng(f, it); o.put("image", f); keepFiles += f }
            arr.put(o)
        }
        File(feedDir, "feed.json").writeText(arr.toString())
        // Post pictures no longer in the feed; source icons (src_*) are managed by Rss.
        feedDir.listFiles()?.forEach { if ((it.name.startsWith("a_") || it.name.startsWith("i_")) && it.name !in keepFiles) it.delete() }
    }

    private fun writePng(name: String, b: Bitmap) {
        val f = File(feedDir, name)
        if (f.exists()) return
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun loadFeed(): List<EventItem> = runCatching {
        val f = File(feedDir, "feed.json")
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            fun bmp(k: String) = o.optString(k).takeIf { it.isNotEmpty() }?.let { BitmapFactory.decodeFile(File(feedDir, it).path) }
            EventItem(
                key = o.getString("key"), pkg = o.optString("pkg"), title = o.optString("title"),
                body = o.optString("body").takeIf { it.isNotEmpty() }, subtitle = null, time = o.getLong("time"),
                icon = bmp("icon"), image = bmp("image"), intent = null,
                link = o.optString("link").takeIf { it.isNotEmpty() }, source = o.optString("source").takeIf { it.isNotEmpty() },
                clearable = true, origin = o.optString("origin").takeIf { it.isNotEmpty() },
            )
        }
    }.getOrDefault(emptyList())

    // ---- refresh ----

    /** The user's own Reddit app ID (Home settings), used only to read subreddit and user icons. */
    val redditClientId: String? get() = prefs.getString("reddit_client_id", null)?.trim()?.ifEmpty { null }
    fun setRedditClientId(id: String?) {
        prefs.edit().putString("reddit_client_id", id?.trim()?.ifEmpty { null }).apply()
        Rss.forgetRedditToken()
        // Fetch again so posts swap their letter tiles for the real icons.
        refresh(force = true)
    }

    val rssSources: List<String> get() = prefs.getString("rss", "")!!.lines().map { it.trim() }.filter { it.startsWith("http") }

    /** The name a source goes by in settings: its feed's own title once read. */
    fun sourceName(url: String): String = runCatching { JSONObject(prefs.getString("rss_names", "{}")!!).optString(url) }.getOrNull()
        ?.takeIf { it.isNotEmpty() } ?: runCatching { java.net.URL(url).host.removePrefix("www.") }.getOrDefault(url)

    private fun setSourceName(url: String, name: String) {
        val o = runCatching { JSONObject(prefs.getString("rss_names", "{}")!!) }.getOrDefault(JSONObject())
        o.put(url, name)
        prefs.edit().putString("rss_names", o.toString()).apply()
    }

    private const val NL = "\n"

    /** Add a source; [items] are its posts as already read when it was checked. */
    fun addSource(url: String, name: String?, items: List<EventItem>) {
        if (url !in rssSources) prefs.edit().putString("rss", (rssSources + url).joinToString(NL)).apply()
        name?.let { setSourceName(url, it) }
        if (items.isNotEmpty()) mergeFeed(items)
    }

    private var retryPosted = false

    fun removeSource(url: String) {
        prefs.edit().putString("rss", rssSources.filter { it != url }.joinToString(NL)).apply()
        Rss.forgetIcon(app, url)
        prune()
    }

    var refreshing = false
        private set

    /** "Refresh": re-read active notifications and fetch the feed sources. */
    fun refresh(force: Boolean = false) {
        service?.let { s -> runCatching { setActive(s.activeNotifications ?: emptyArray()) } }
        prune()
        if (refreshing && !force) return
        val sources = rssSources
        if (sources.isEmpty()) return
        refreshing = true; changed()
        io.execute {
            val fresh = ArrayList<EventItem>()
            var failed = 0
            for (url in sources) runCatching {
                val f = Rss.fetch(app, url)
                fresh += f.items
                setSourceName(url, f.title)
            }.onFailure { failed++ }
            prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
            main.post {
                refreshing = false
                // Sources that couldn't be read (busy, offline) are tried again soon, not in an hour.
                if (failed > 0 && !retryPosted) {
                    retryPosted = true
                    main.postDelayed({ retryPosted = false; refresh() }, RETRY_MS)
                }
                // A source removed while this was fetching doesn't come back.
                val now = rssSources.toSet()
                val keep = fresh.filter { it.origin in now }
                if (keep.isNotEmpty()) mergeFeed(keep) else changed()
            }
        }
    }

    fun maybeAutoUpdate() {
        if (System.currentTimeMillis() - prefs.getLong("last_update", 0L) >= AUTO_UPDATE_MS) refresh()
    }
}
