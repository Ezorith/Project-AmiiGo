package com.amiigo.harmattan

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Third-party icon packs in the common ADW / Nova format: an `appfilter` (res/xml or assets) mapping
 * `ComponentInfo{package/activity}` to drawable names, plus optional iconback / iconmask / iconupon /
 * scale used to theme apps the pack doesn't cover.
 */
object IconPacks {
    class Pack(val pkg: String, val label: String)

    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME",
        "com.gau.go.launcherex.theme", "org.adw.launcher.icons.ACTION_PICK_ICON", "com.anddoes.launcher.THEME",
    )

    fun installed(ctx: Context): List<Pack> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, Pack>()
        for (a in ACTIONS) {
            for (ri in pm.queryIntentActivities(Intent(a), PackageManager.GET_META_DATA)) {
                val p = ri.activityInfo.packageName
                if (p !in seen) seen[p] = Pack(p, ri.loadLabel(pm).toString())
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    /** A loaded pack: lookups by component, and the generic theming layers. */
    class Loaded(ctx: Context, val pkg: String) {
        private val res: Resources = ctx.packageManager.getResourcesForApplication(pkg)
        private val map = HashMap<String, String>()   // "package/activity" -> drawable name
        private val pkgMap = HashMap<String, String>() // "package" -> drawable name (first seen)
        private var back: String? = null
        private var mask: String? = null
        private var upon: String? = null
        private var scale = 1f
        private val density = ctx.resources.displayMetrics.densityDpi

        init {
            val parser: XmlPullParser? = run {
                val id = res.getIdentifier("appfilter", "xml", pkg)
                if (id != 0) res.getXml(id) else runCatching {
                    val s = ctx.createPackageContext(pkg, 0).assets.open("appfilter.xml")
                    XmlPullParserFactory.newInstance().newPullParser().apply { setInput(s, "utf-8") }
                }.getOrNull()
            }
            if (parser != null) runCatching {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    when (parser.name) {
                        "item" -> {
                            val comp = parser.getAttributeValue(null, "component") ?: continue
                            val dr = parser.getAttributeValue(null, "drawable") ?: continue
                            val c = comp.removePrefix("ComponentInfo{").removeSuffix("}")
                            map[c] = dr
                            c.substringBefore('/').let { if (it !in pkgMap) pkgMap[it] = dr }
                        }
                        "iconback" -> back = parser.getAttributeValue(null, "img1") ?: parser.getAttributeValue(null, "img")
                        "iconmask" -> mask = parser.getAttributeValue(null, "img1") ?: parser.getAttributeValue(null, "img")
                        "iconupon" -> upon = parser.getAttributeValue(null, "img1") ?: parser.getAttributeValue(null, "img")
                        "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                    }
                }
            }
        }

        private fun drawable(name: String?): Drawable? {
            if (name == null) return null
            val id = res.getIdentifier(name, "drawable", pkg).takeIf { it != 0 } ?: res.getIdentifier(name, "mipmap", pkg)
            if (id == 0) return null
            return runCatching { res.getDrawableForDensity(id, density, null) }.getOrNull()
        }

        /** The pack's own icon for this activity (falls back to one for the same app). */
        fun iconFor(c: ComponentName): Drawable? =
            drawable(map[c.packageName + "/" + c.className]) ?: drawable(pkgMap[c.packageName])

        val themesOthers: Boolean get() = back != null || mask != null || upon != null

        /** Theme an icon the pack doesn't cover: back plate, scaled icon, cut by the mask, upon layer. */
        fun themed(icon: Drawable, size: Int): Bitmap? {
            if (!themesOthers) return null
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            drawable(back)?.let { it.setBounds(0, 0, size, size); it.draw(c) }
            val inner = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val ic = Canvas(inner)
            val s = (size * scale).toInt(); val o = (size - s) / 2
            icon.setBounds(o, o, o + s, o + s); icon.draw(ic)
            drawable(mask)?.let { m ->
                val mb = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                m.setBounds(0, 0, size, size); m.draw(Canvas(mb))
                ic.drawBitmap(mb, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            }
            c.drawBitmap(inner, null, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
            drawable(upon)?.let { it.setBounds(0, 0, size, size); it.draw(c) }
            return out
        }
    }
}
