package com.amiigo.harmattan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * RSS 2.0 / Atom reader for the events feed (the N9 feed showed Feeds-app RSS items next to
 * Facebook and Twitter updates). Many social services publish feeds: Mastodon (`/@user.rss`),
 * Bluesky (`/profile/<handle>/rss`), YouTube channels, Reddit (`.rss`), blogs and forums.
 */
object Rss {
    /** Networks that publish public per-account feeds, and how to turn a name into the feed address. */
    enum class Kind(val label: String, val hint: String) {
        BLUESKY("Bluesky account", "handle, e.g. nokia.bsky.social"),
        MASTODON("Mastodon account", "@user@instance, e.g. @nokia@mastodon.social"),
        YOUTUBE("YouTube channel", "@handle or channel link"),
        REDDIT("Reddit", "r/subreddit or u/username"),
        TUMBLR("Tumblr blog", "blog name"),
        WEBSITE("Website or feed address", "e.g. geekzone.co.nz or a .rss link"),
    }

    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    /** A feed as read: its own title, its home page, its picture, and the newest posts. */
    class Feed(val title: String, val items: List<EventItem>)

    /** What adding a source found: the feed address and its title, or why it failed. */
    class Found(val url: String?, val title: String?, val error: String?, val feed: Feed? = null)

    /**
     * Work out the feed address for [input] and check it really has posts. Network; call off the
     * main thread (the callback also runs off it).
     */
    fun resolve(ctx: Context, kind: Kind, input: String, done: (Found) -> Unit) {
        Thread {
            val v = input.trim().removePrefix("https://").removePrefix("http://")
            val url: String? = runCatching {
                when (kind) {
                    Kind.BLUESKY -> "https://bsky.app/profile/" + v.removePrefix("bsky.app/profile/").removePrefix("@").substringBefore('/') + "/rss"
                    Kind.MASTODON -> {
                        val parts = v.removePrefix("@").split('@')
                        if (parts.size == 2) "https://${parts[1]}/@${parts[0]}.rss"
                        else if (v.contains("/@")) "https://" + v.substringBefore('?').removeSuffix("/") + ".rss" else null
                    }
                    Kind.REDDIT -> {
                        val s = v.removePrefix("www.reddit.com/").removePrefix("reddit.com/").trim('/')
                        when {
                            s.startsWith("r/") -> "https://www.reddit.com/${s.substringBefore('?')}/.rss"
                            s.startsWith("u/") || s.startsWith("user/") -> "https://www.reddit.com/user/${s.substringAfter('/')}/.rss"
                            else -> "https://www.reddit.com/r/$s/.rss"
                        }
                    }
                    Kind.TUMBLR -> "https://" + v.removeSuffix(".tumblr.com").substringBefore('/') + ".tumblr.com/rss"
                    Kind.YOUTUBE -> {
                        Regex("(UC[\\w-]{22})").find(v)?.value?.let { "https://www.youtube.com/feeds/videos.xml?channel_id=$it" }
                            ?: discover(if (v.contains("youtube.com")) "https://$v" else "https://www.youtube.com/@" + v.removePrefix("@"))
                    }
                    // A feed address as it is; otherwise the feed the page itself advertises.
                    Kind.WEBSITE -> "https://$v".let { if (runCatching { read(it, 1) }.isSuccess) it else discover(it) }
                }
            }.getOrNull()
            if (url == null) { done(Found(null, null, "Couldn't find a feed for $input")); return@Thread }
            // Check it: an address that can't be read or has no posts isn't worth adding.
            // Read it once, posts and all: that both checks it and fills the feed straight away
            // (a second request right after would often be refused by rate-limited sites like Reddit).
            val r = runCatching { fetch(ctx, url) }
            val feed = r.getOrNull()
            done(when {
                feed == null -> Found(null, null,
                    if (r.exceptionOrNull() is Busy) "${URL(url).host.removePrefix("www.")} is busy — try again in a minute" else "Couldn't read posts from $input")
                feed.items.isEmpty() -> Found(null, null, "$input has no public posts")
                else -> Found(url, feed.title.ifEmpty { null }, null, feed)
            })
        }.start()
    }

    /** The RSS/Atom feed a web page links to (`<link rel="alternate" type="application/rss+xml">`). */
    private fun discover(page: String): String? {
        val html = get(page).decodeToString()
        val base = URL(page)
        for (m in Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.value
            if (!Regex("rel=[\"']?alternate", RegexOption.IGNORE_CASE).containsMatchIn(tag)) continue
            if (!Regex("type=[\"']?application/(rss|atom)\\+xml", RegexOption.IGNORE_CASE).containsMatchIn(tag)) continue
            val href = Regex("href=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: continue
            return URL(base, Html.fromHtml(href, Html.FROM_HTML_MODE_COMPACT).toString()).toString()
        }
        // YouTube pages also carry the channel id in their data.
        Regex("channel_id=(UC[\\w-]{22})|\"(?:channelId|externalId)\":\"(UC[\\w-]{22})\"").find(html)?.let {
            val id = it.groupValues[1].ifEmpty { it.groupValues[2] }
            return "https://www.youtube.com/feeds/videos.xml?channel_id=$id"
        }
        return null
    }

    /** The site kept refusing with "too many requests". */
    class Busy(url: String) : java.io.IOException("rate limited: $url")

    // When each site was last asked, so requests to one site are spaced out.
    private val lastAsked = HashMap<String, Long>()
    private const val SPACING_MS = 2500L

    /**
     * Connect, spacing requests to the same site and, when it says it's busy (429/503), waiting as
     * long as it asks (Retry-After / x-ratelimit-reset, at least 3s) and trying again, a few times.
     */
    private fun open(url: String, space: Boolean = true): HttpURLConnection {
        val host = URL(url).host
        var attempt = 0
        while (true) {
            if (space) synchronized(lastAsked) {
                val wait = (lastAsked[host] ?: 0L) + SPACING_MS - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                lastAsked[host] = System.currentTimeMillis()
            }
            val c = connect(url)
            val code = runCatching { c.responseCode }.getOrDefault(-1)
            if (code != 429 && code != 503) return c
            val asked = (c.getHeaderField("Retry-After") ?: c.getHeaderField("x-ratelimit-reset"))?.trim()?.toDoubleOrNull()
            c.disconnect()
            if (++attempt >= 4) throw Busy(url)
            Thread.sleep(((asked ?: 0.0) * 1000).toLong().coerceIn(3000L, 20_000L) * attempt)
        }
    }

    private fun connect(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 15_000
        // A desktop browser's identity: some sites (YouTube) send phones to a different page.
        setRequestProperty("User-Agent", BROWSER_UA)
        setRequestProperty("Cookie", "CONSENT=YES+")
        instanceFollowRedirects = true
    }

    /** Pages, pictures and icons: retried when busy, but not spaced like feed reads. */
    private fun get(url: String): ByteArray = open(url, space = false).inputStream.use { it.readBytes() }

    private class Raw(
        var title: String = "", var link: String = "", var body: String = "", var date: String = "", var image: String = "",
        /** Reddit: the subreddit a post is in (`<category term>`). */
        var category: String = "",
    )
    private class Parsed(val title: String, val link: String, val image: String, val posts: List<Raw>, val authorUri: String = "")

    private fun read(url: String, max: Int = 20): Parsed {
        val items = ArrayList<Raw>()
        var channel = ""
        var channelLink = ""
        var channelImage = ""
        var authorUri = ""
        var any = false
        open(url).inputStream.use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            var cur: Raw? = null
            var titleSet = false
            var inImage = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.END_TAG) {
                    if (p.name == "item" || p.name == "entry") { cur?.let { items += it }; cur = null; if (items.size >= max) break }
                    if (p.name == "image") inImage = false
                    continue
                }
                if (p.eventType != XmlPullParser.START_TAG) continue
                val name = p.name
                if (name == "rss" || name == "feed" || name == "rdf:RDF") any = true
                when {
                    name == "item" || name == "entry" -> cur = Raw()
                    cur == null -> when (name) {
                        "image" -> inImage = true
                        "url" -> if (inImage && channelImage.isEmpty()) channelImage = text(p)
                        "icon", "logo" -> if (channelImage.isEmpty()) channelImage = text(p)
                        "uri" -> if (authorUri.isEmpty()) authorUri = text(p)
                        "title" -> if (!titleSet && !inImage) { channel = text(p); titleSet = true }
                        "link" -> if (channelLink.isEmpty() && !inImage) {
                            val href = p.getAttributeValue(null, "href")
                            val rel = p.getAttributeValue(null, "rel")
                            if (href != null) { if (rel == null || rel == "alternate") channelLink = href } else channelLink = text(p)
                        }
                    }
                    else -> when (name) {
                        "title" -> cur.title = text(p)
                        "link" -> {
                            val href = p.getAttributeValue(null, "href")
                            val rel = p.getAttributeValue(null, "rel")
                            if (href != null) { if (rel == null || rel == "alternate") cur.link = href } else cur.link = text(p)
                        }
                        "description", "summary", "content", "content:encoded", "media:description" -> { val t = text(p); if (t.length > cur.body.length) cur.body = t }
                        "pubDate", "published", "updated", "dc:date" -> if (cur.date.isEmpty()) cur.date = text(p)
                        "media:thumbnail", "media:content" -> if (cur.image.isEmpty()) p.getAttributeValue(null, "url")?.let {
                            val medium = p.getAttributeValue(null, "medium")
                            if (name == "media:thumbnail" || medium == null || medium == "image") cur.image = it
                        }
                        "category" -> if (cur.category.isEmpty()) cur.category = p.getAttributeValue(null, "term") ?: ""
                        "enclosure" -> if (cur.image.isEmpty() && p.getAttributeValue(null, "type")?.startsWith("image") == true)
                            cur.image = p.getAttributeValue(null, "url") ?: ""
                    }
                }
            }
        }
        if (!any) throw IllegalArgumentException("not a feed")
        return Parsed(channel, channelLink, channelImage, items, authorUri)
    }

    fun fetch(ctx: Context, url: String): Feed {
        val f = read(url)
        val host = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault(url)
        val source = Html.fromHtml(f.title, Html.FROM_HTML_MODE_COMPACT).toString().ifEmpty { host }
        val home = f.link.ifEmpty { f.posts.firstOrNull()?.link.orEmpty() }
        val service = serviceOf(url)
        // The service's own icon goes on top as a small badge; for a plain website its icon is the picture.
        val badge = if (service != null) badgeFor(ctx, service, url) else null
        val sourcePic = runCatching { sourceAvatar(ctx, service, url, f) }.getOrNull()
            ?: if (service == null) runCatching { siteIconCached(ctx, home.takeIf { it.startsWith("http") } ?: url) }.getOrNull() else null
        val sourceIcon = compose(ctx, sourcePic, badge)
        runCatching { iconFile(ctx, url).outputStream().use { sourceIcon.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        // Subreddit feeds like r/popular mix communities: each post shows its own subreddit.
        val ownSub = Regex("reddit\\.com/r/([^/]+)").find(url)?.groupValues?.get(1)?.lowercase()
        val perSub = HashMap<String, Bitmap>()
        val items = f.posts.map { r ->
            val icon = if (service == Service.REDDIT && ownSub != null && r.category.isNotEmpty() && r.category.lowercase() != ownSub)
                perSub.getOrPut(r.category.lowercase()) { compose(ctx, redditIcon(ctx, "r", r.category) ?: monogram("r/" + r.category), badge) }
            else sourceIcon
            if (r.image.isEmpty()) Regex("<img[^>]+src=[\"']([^\"']+)").find(r.body)?.let { r.image = it.groupValues[1] }
            val body = Html.fromHtml(r.body, Html.FROM_HTML_MODE_COMPACT).toString().replace('￼', ' ')
                // Reddit's boilerplate under every post.
                .replace(Regex("\\[link\\]\\s*\\[comments\\]"), "").replace(Regex("submitted by\\s+/u/"), "u/")
                .trim().take(400)
            EventItem(
                key = "rss:" + (r.link.ifEmpty { r.title }), pkg = "", title = Html.fromHtml(r.title, Html.FROM_HTML_MODE_COMPACT).toString().ifEmpty { source },
                body = body.ifEmpty { null }, subtitle = null, time = parseDate(r.date), icon = icon,
                image = r.image.takeIf { it.startsWith("http") }?.let { download(it) }, intent = null,
                link = r.link.ifEmpty { null }, source = source, clearable = true, origin = url,
            )
        }
        return Feed(source, items)
    }

    // ---- where the posts come from: its icon ----

    /** Services whose posts get the account's picture plus the service's badge. */
    enum class Service { REDDIT, YOUTUBE, MASTODON, BLUESKY, TUMBLR }

    private fun serviceOf(url: String): Service? {
        val host = runCatching { URL(url).host.lowercase().removePrefix("www.") }.getOrDefault("")
        return when {
            host == "reddit.com" || host.endsWith(".reddit.com") -> Service.REDDIT
            host == "youtube.com" || host.endsWith(".youtube.com") -> Service.YOUTUBE
            host == "bsky.app" -> Service.BLUESKY
            host.endsWith(".tumblr.com") -> Service.TUMBLR
            url.contains("/@") && url.endsWith(".rss") -> Service.MASTODON
            else -> null
        }
    }

    private val SERVICE_APPS = mapOf(
        Service.REDDIT to listOf("com.reddit.frontpage"), Service.YOUTUBE to listOf("com.google.android.youtube"),
        Service.BLUESKY to listOf("xyz.blueskyweb.app"), Service.TUMBLR to listOf("com.tumblr"),
        Service.MASTODON to listOf("org.joinmastodon.android", "org.joinmastodon.android.moshidon", "com.keylesspalace.tusky"),
    )
    /** Apps that show posts from the site [link] points to, best first (installed or not). */
    fun appsForLink(link: String): List<String> {
        val host = runCatching { URL(link).host.lowercase().removePrefix("www.").removePrefix("m.") }.getOrDefault("")
        fun on(vararg hosts: String) = hosts.any { host == it || host.endsWith(".$it") }
        return when {
            on("reddit.com", "redd.it") -> SERVICE_APPS.getValue(Service.REDDIT)
            on("youtube.com", "youtu.be") -> SERVICE_APPS.getValue(Service.YOUTUBE)
            on("bsky.app") -> SERVICE_APPS.getValue(Service.BLUESKY)
            on("tumblr.com") -> SERVICE_APPS.getValue(Service.TUMBLR)
            on("x.com", "twitter.com") -> listOf("com.twitter.android")
            on("facebook.com", "fb.com") -> listOf("com.facebook.katana", "com.facebook.lite")
            on("instagram.com") -> listOf("com.instagram.android")
            on("threads.net", "threads.com") -> listOf("com.instagram.barcelona")
            link.contains("/@") -> SERVICE_APPS.getValue(Service.MASTODON)
            else -> emptyList()
        }
    }

    private val SERVICE_HOME = mapOf(
        Service.REDDIT to "https://www.reddit.com/", Service.YOUTUBE to "https://www.youtube.com/",
        Service.BLUESKY to "https://bsky.app/", Service.TUMBLR to "https://www.tumblr.com/", Service.MASTODON to "https://joinmastodon.org/",
    )

    private fun feedFile(ctx: Context, name: String) = File(File(ctx.filesDir, "feed").apply { mkdirs() }, name)
    private fun iconFile(ctx: Context, url: String) = feedFile(ctx, "src_${Integer.toHexString(url.hashCode())}.png")
    private const val KEEP_MS = 7 * 24 * 3600_000L

    /** A picture kept on disk for a week, so each one is looked up once (not on every refresh). */
    private fun cached(ctx: Context, name: String, make: () -> Bitmap?): Bitmap? {
        val f = feedFile(ctx, name)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < KEEP_MS) BitmapFactory.decodeFile(f.path)?.let { return it }
        val b = make() ?: return null
        runCatching { f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return b
    }

    /** The saved icon of the feed at [url], if it has been read. */
    fun savedIcon(ctx: Context, url: String): Bitmap? = iconFile(ctx, url).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun forgetIcon(ctx: Context, url: String) {
        iconFile(ctx, url).delete()
        feedFile(ctx, "av_${Integer.toHexString(url.hashCode())}.png").delete()
    }

    /** The service's badge: its app's icon if installed, else its website's icon. */
    private fun badgeFor(ctx: Context, service: Service, url: String): Bitmap? {
        SERVICE_APPS[service]?.firstOrNull { p -> Apps.installed.any { it.pkg == p } }?.let { p -> EventStore.appBadge(p)?.let { return it } }
        val site = if (service == Service.MASTODON) "https://" + URL(url).host + "/" else SERVICE_HOME.getValue(service)
        return runCatching { siteIconCached(ctx, site) }.getOrNull()
    }

    private fun siteIconCached(ctx: Context, page: String): Bitmap? =
        cached(ctx, "site_${Integer.toHexString(URL(page).host.hashCode())}.png") { siteIcon(page) }

    /**
     * The picture of who the posts are from. YouTube: the channel's picture (its page's og:image);
     * Mastodon: the account picture the feed carries; Bluesky: the public profile; Tumblr: the blog's
     * avatar. Reddit only shows subreddit and user icons to signed-in requests, so those get a monogram.
     */
    private fun sourceAvatar(ctx: Context, service: Service?, url: String, f: Parsed): Bitmap? {
        val key = "av_${Integer.toHexString(url.hashCode())}.png"
        return when (service) {
            null -> null
            Service.REDDIT -> {
                val m = Regex("reddit\\.com/(r|u|user)/([^/?]+)").find(url) ?: return null
                val kind = if (m.groupValues[1] == "r") "r" else "u"
                redditIcon(ctx, kind, m.groupValues[2]) ?: monogram("$kind/" + m.groupValues[2])
            }
            Service.MASTODON -> cached(ctx, key) { f.image.takeIf { it.startsWith("http") }?.let { decode(get(it), N9.ipx(64)) } }
            Service.YOUTUBE -> cached(ctx, key) {
                val id = Regex("channel_id=(UC[\\w-]{22})").find(url)?.groupValues?.get(1)
                    ?: Regex("(UC[\\w-]{22})").find(f.authorUri)?.value ?: return@cached null
                val html = get("https://www.youtube.com/channel/$id").decodeToString()
                Regex("<meta property=\"og:image\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                    ?.let { decode(get(Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString()), N9.ipx(64)) }
            }
            Service.BLUESKY -> cached(ctx, key) {
                val handle = Regex("bsky\\.app/profile/([^/]+)").find(url)?.groupValues?.get(1) ?: return@cached null
                val json = org.json.JSONObject(get("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=" +
                    java.net.URLEncoder.encode(handle, "UTF-8")).decodeToString())
                json.optString("avatar").takeIf { it.startsWith("http") }?.let { decode(get(it), N9.ipx(64)) }
            }
            Service.TUMBLR -> cached(ctx, key) {
                decode(get("https://api.tumblr.com/v2/blog/" + URL(url).host + "/avatar/128"), N9.ipx(64))
            }
        }
    }

    // ---- Reddit icons, through Reddit's API ----
    //
    // Reddit shows subreddit and user icons only to browsers that pass its check, or to apps using its
    // API. With an app ID the user creates on their Reddit account (an "installed app"), this asks
    // for an app-only token (no sign-in) and reads each community's icon from /r/<name>/about.

    private const val REDDIT_UA = "android:com.amiigo.harmattan:1.0 (Harmattan Home feed icons)"
    private var redditToken: String? = null
    private var redditTokenUntil = 0L
    private var redditDevice: String? = null

    /** A token for the app ID in settings, or null (no ID, or Reddit refused it). */
    @Synchronized
    fun redditToken(clientId: String? = EventStore.redditClientId): String? {
        if (clientId.isNullOrBlank()) return null
        if (redditToken != null && System.currentTimeMillis() < redditTokenUntil) return redditToken
        val device = redditDevice ?: java.util.UUID.randomUUID().toString().replace("-", "").take(30).also { redditDevice = it }
        val c = (URL("https://www.reddit.com/api/v1/access_token").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            requestMethod = "POST"; doOutput = true
            setRequestProperty("User-Agent", REDDIT_UA)
            setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("${clientId.trim()}:".toByteArray(), android.util.Base64.NO_WRAP))
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        c.outputStream.use { it.write("grant_type=https%3A%2F%2Foauth.reddit.com%2Fgrants%2Finstalled_client&device_id=$device".toByteArray()) }
        if (c.responseCode != 200) { c.disconnect(); return null }
        val json = org.json.JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
        redditToken = json.optString("access_token").ifEmpty { null }
        redditTokenUntil = System.currentTimeMillis() + (json.optLong("expires_in", 3600) - 60) * 1000
        return redditToken
    }

    fun forgetRedditToken() { synchronized(this) { redditToken = null; redditTokenUntil = 0L } }

    /** A subreddit's ([kind] "r") or user's ("u") icon, kept for a week; null without an app ID. */
    private fun redditIcon(ctx: Context, kind: String, name: String): Bitmap? {
        if (EventStore.redditClientId.isNullOrBlank()) return null
        return cached(ctx, "rd_${kind}_${name.lowercase()}.png") {
            val token = redditToken() ?: return@cached null
            val path = if (kind == "r") "r/$name/about" else "user/$name/about"
            val c = (URL("https://oauth.reddit.com/$path?raw_json=1").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("User-Agent", REDDIT_UA)
                setRequestProperty("Authorization", "bearer $token")
            }
            if (c.responseCode != 200) { c.disconnect(); return@cached null }
            val data = org.json.JSONObject(c.inputStream.use { it.readBytes().decodeToString() }).optJSONObject("data") ?: return@cached null
            val icon = listOf("community_icon", "icon_img", "snoovatar_img")
                .map { data.optString(it) }
                .firstOrNull { it.startsWith("http") } ?: return@cached null
            decode(get(Html.fromHtml(icon, Html.FROM_HTML_MODE_COMPACT).toString()), N9.ipx(64))
        }
    }

    private val MONO_COLORS = intArrayOf(
        0xFF1A9BFF.toInt(), 0xFF2E7D32.toInt(), 0xFFC62828.toInt(), 0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(),
        0xFF00838F.toInt(), 0xFFAD1457.toInt(), 0xFF4E342E.toInt(), 0xFF283593.toInt(), 0xFF558B2F.toInt(),
    )

    /** A stand-in picture: the name's first letter on a colour picked from the name. */
    fun monogram(name: String): Bitmap {
        val size = N9.ipx(64)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(MONO_COLORS[Math.floorMod(name.lowercase().hashCode(), MONO_COLORS.size)])
        val letter = name.substringAfter('/').firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        val p = N9.textPaint(N9.bold, 34f, android.graphics.Color.WHITE, scaled = false)
        val fm = p.fontMetrics
        c.drawText(letter, (size - p.measureText(letter)) / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
        return b
    }

    /** The feed icon: the picture in the N9 avatar mask, with the service badge at its bottom right. */
    private fun compose(ctx: Context, picture: Bitmap?, badge: Bitmap?): Bitmap {
        if (picture == null) return EventStore.masked(badge ?: Bitmap.createScaledBitmap(N9.bitmap(ctx, R.drawable.n9_app_rss), 64, 64, true))
        return EventStore.withBadge(EventStore.masked(picture), badge)
    }

    /** A site's own icon: the largest apple-touch-icon / icon its home page names, else /favicon.ico. */
    private fun siteIcon(page: String): Bitmap? {
        val root = URL(URL(page), "/").toString()
        val html = runCatching { get(root).decodeToString() }.getOrDefault("")
        val candidates = ArrayList<Pair<Int, String>>()
        for (m in Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.value
            val rel = Regex("rel=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase() ?: continue
            if ("icon" !in rel) continue
            val href = Regex("href=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: continue
            if (href.endsWith(".svg")) continue
            val size = Regex("sizes=[\"'](\\d+)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: if ("apple-touch" in rel) 180 else 32
            candidates += size to URL(URL(root), Html.fromHtml(href, Html.FROM_HTML_MODE_COMPACT).toString()).toString()
        }
        candidates += 16 to URL(URL(root), "/favicon.ico").toString()
        for ((_, u) in candidates.sortedByDescending { it.first }) {
            val b = runCatching { decode(get(u), N9.ipx(64)) }.getOrNull()
            if (b != null && b.width >= 16) return b
        }
        return null
    }

    private fun decode(bytes: ByteArray, target: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- parsing helpers ----

    private fun text(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(p.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString().trim()
    }

    private val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd",
    )

    private fun parseDate(s: String): Long {
        for (f in formats) runCatching { return SimpleDateFormat(f, Locale.US).parse(s)!!.time }
        return System.currentTimeMillis()
    }

    private fun download(url: String): Bitmap? = runCatching {
        decode(get(url), N9.ipx(168))?.let { EventStore.scaleDown(it, N9.ipx(168)) }
    }.getOrNull()
}
