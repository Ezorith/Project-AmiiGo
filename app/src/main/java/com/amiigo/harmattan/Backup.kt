package com.amiigo.harmattan

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Back up and restore home's setup as one file: app order, folders, hidden apps, sizes and icon
 * settings, the wallpaper (picture and settings), feed sources and feed apps, and the weather place.
 * Recents and other passing state (recently closed apps, last feed update) are left out.
 */
object Backup {
    private const val FORMAT = "harmattan-home-backup"
    private val FILES = listOf("home", "events", "weather")

    /** Keys that describe the moment, not the setup. */
    private fun skip(file: String, key: String) = when (file) {
        "home" -> key == "recents" || key.startsWith("closed:")
        "events" -> key == "last_update"
        "weather" -> key in setOf("temp_c", "temp", "code", "day", "at")
        else -> false
    }

    fun suggestedName(): String =
        "Harmattan Home backup " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"

    /** Write the backup to [uri]. Blocking. */
    fun save(ctx: Context, uri: Uri): Boolean = runCatching {
        val root = JSONObject().put("format", FORMAT).put("version", 1)
            .put("app", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName)
            .put("saved", System.currentTimeMillis())
        val prefs = JSONObject()
        for (f in FILES) {
            val o = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(f, Context.MODE_PRIVATE).all) {
                if (v == null || skip(f, k)) continue
                o.put(k, when (v) {
                    is Boolean -> JSONObject().put("b", v)
                    is Int -> JSONObject().put("i", v)
                    is Long -> JSONObject().put("l", v)
                    is Float -> JSONObject().put("f", v.toDouble())
                    is String -> JSONObject().put("s", v)
                    is Set<*> -> JSONObject().put("ss", JSONArray(v.map { it.toString() }))
                    else -> continue
                })
            }
            prefs.put(f, o)
        }
        root.put("prefs", prefs)
        File(ctx.filesDir, "wallpaper.jpg").takeIf { it.exists() }?.let {
            root.put("wallpaper", Base64.encodeToString(it.readBytes(), Base64.NO_WRAP))
        }
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(root.toString().toByteArray()) }
        true
    }.getOrDefault(false)

    /** What a backup at [uri] holds, or null if it isn't one. Blocking. */
    fun read(ctx: Context, uri: Uri): JSONObject? = runCatching {
        val o = JSONObject(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
        o.takeIf { it.optString("format") == FORMAT && it.has("prefs") }
    }.getOrNull()

    /**
     * Replace the setup with [backup]'s (Recents stay as they are). Blocking; home has to restart
     * afterwards, as it keeps its settings in memory.
     */
    fun restore(ctx: Context, backup: JSONObject) {
        val prefs = backup.getJSONObject("prefs")
        for (f in FILES) {
            val sp = ctx.getSharedPreferences(f, Context.MODE_PRIVATE)
            val e = sp.edit()
            for (k in sp.all.keys) if (!skip(f, k)) e.remove(k)
            val o = prefs.optJSONObject(f) ?: JSONObject()
            for (k in o.keys()) {
                if (skip(f, k)) continue
                val v = o.getJSONObject(k)
                when {
                    v.has("b") -> e.putBoolean(k, v.getBoolean("b"))
                    v.has("i") -> e.putInt(k, v.getInt("i"))
                    v.has("l") -> e.putLong(k, v.getLong("l"))
                    v.has("f") -> e.putFloat(k, v.getDouble("f").toFloat())
                    v.has("s") -> e.putString(k, v.getString("s"))
                    v.has("ss") -> { val a = v.getJSONArray("ss"); e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            e.commit()
        }
        val wall = File(ctx.filesDir, "wallpaper.jpg")
        val pic = backup.optString("wallpaper", "")
        if (pic.isEmpty()) wall.delete()
        else {
            val tmp = File(ctx.filesDir, "wallpaper.tmp")
            tmp.writeBytes(Base64.decode(pic, Base64.NO_WRAP))
            tmp.renameTo(wall)
            if (Wallpaper.shareWithSystem(ctx)) Wallpaper.applyToSystem(ctx)
        }
        // The feed is read again from the restored sources.
        ctx.getSharedPreferences("events", Context.MODE_PRIVATE).edit().putLong("last_update", 0L).commit()
    }
}
