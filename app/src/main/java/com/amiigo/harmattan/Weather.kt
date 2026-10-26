package com.amiigo.harmattan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Weather for the Today header, where the N9 showed AccuWeather's widget (libevents-weather: city,
 * temperature, condition icon). Android has no shared weather service and Samsung Weather keeps its
 * data to itself, so the conditions come from Open-Meteo (free, no account), for either the phone's
 * coarse location or a city chosen in Home settings.
 */
object Weather {
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<() -> Unit>()

    const val MODE_OFF = "off"
    const val MODE_LOCATION = "location"
    const val MODE_CITY = "city"
    private const val MAX_AGE_MS = 30 * 60_000L

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("weather", Context.MODE_PRIVATE)
        // Readings used to be stored in the chosen unit: fetch one in °C now.
        if (prefs.contains("temp") && !prefs.contains("temp_c")) refresh(force = true)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    private fun changed() = main.post { listeners.forEach { it() } }

    val mode: String get() = prefs.getString("mode", MODE_OFF)!!
    val hasData: Boolean get() = mode != MODE_OFF && prefs.contains("temp_c")
    val city: String get() = prefs.getString("place", "").orEmpty()
    /** In the chosen unit; stored in °C, so switching units never needs a new reading. */
    val temperature: Int get() {
        val c = prefs.getFloat("temp_c", 0f)
        return Math.round(if (fahrenheit) c * 9f / 5f + 32f else c)
    }
    val code: Int get() = prefs.getInt("code", -1)
    val isDay: Boolean get() = prefs.getBoolean("day", true)
    /**
     * °F or °C: the choice in Home settings, else Android's own temperature unit (Regional
     * preferences, Android 14+), else the region's usual unit.
     */
    val fahrenheit: Boolean get() = when (prefs.getString("unit", null)) {
        "f" -> true
        "c" -> false
        else -> when (Locale.getDefault().getUnicodeLocaleType("mu")) {
            "fahrenhe" -> true
            "celsius" -> false
            else -> Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")
        }
    }
    fun setUnit(f: Boolean) { prefs.edit().putString("unit", if (f) "f" else "c").apply(); changed() }

    fun hasLocationPermission() = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun keepUnit(e: SharedPreferences.Editor): SharedPreferences.Editor {
        prefs.getString("unit", null)?.let { e.putString("unit", it) }
        return e
    }

    fun setOff() { keepUnit(prefs.edit().clear()).putString("mode", MODE_OFF).apply(); changed() }

    fun useLocation() {
        keepUnit(prefs.edit().clear()).putString("mode", MODE_LOCATION).apply()
        refresh(force = true)
    }

    /** Find [query] with Open-Meteo's geocoder; [done] gets "Name, Country" or null (main thread). */
    fun useCity(query: String, done: (String?) -> Unit) {
        io.execute {
            val found = runCatching {
                val j = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + URLEncoder.encode(query.trim(), "UTF-8")))
                val r = j.optJSONArray("results")?.optJSONObject(0) ?: return@runCatching null
                val name = r.getString("name")
                keepUnit(prefs.edit().clear()).putString("mode", MODE_CITY).putString("place", name)
                    .putFloat("lat", r.getDouble("latitude").toFloat()).putFloat("lon", r.getDouble("longitude").toFloat()).apply()
                name + (r.optString("country").takeIf { it.isNotEmpty() }?.let { ", $it" } ?: "")
            }.getOrNull()
            main.post { done(found); if (found != null) refresh(force = true) }
        }
    }

    private var fetching = false

    /** Fetch if the last reading is over 30 minutes old (or [force]). */
    fun refresh(force: Boolean = false) {
        if (!::app.isInitialized || mode == MODE_OFF || fetching) return
        if (!force && System.currentTimeMillis() - prefs.getLong("at", 0L) < MAX_AGE_MS) return
        fetching = true
        io.execute {
            runCatching {
                var lat = prefs.getFloat("lat", Float.NaN).toDouble()
                var lon = prefs.getFloat("lon", Float.NaN).toDouble()
                if (mode == MODE_LOCATION) {
                    val loc = lastLocation() ?: throw IllegalStateException("no location")
                    lat = loc.first; lon = loc.second
                    // The place's name, from Android's own geocoder.
                    @Suppress("DEPRECATION")
                    val place = runCatching { Geocoder(app).getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
                    (place?.locality ?: place?.subAdminArea ?: place?.adminArea)?.let { prefs.edit().putString("place", it).apply() }
                }
                if (lat.isNaN()) throw IllegalStateException("no place")
                val j = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day"
                    .format(Locale.US, lat, lon))).getJSONObject("current")
                prefs.edit().remove("temp").putFloat("temp_c", j.getDouble("temperature_2m").toFloat())
                    .putInt("code", j.getInt("weather_code")).putBoolean("day", j.optInt("is_day", 1) == 1)
                    .putLong("at", System.currentTimeMillis()).apply()
            }
            main.post { fetching = false; changed() }
        }
    }

    @Suppress("MissingPermission")
    private fun lastLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        val lm = app.getSystemService(LocationManager::class.java) ?: return null
        val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        return best?.let { it.latitude to it.longitude }
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", "HarmattanHome/1.0 (Android)")
        }
        return c.inputStream.use { it.readBytes().decodeToString() }
    }

    /**
     * The N9 icon for a WMO weather code, following the AccuWeather mapping the N9 widget used
     * (sunny / partly sunny / cloudy … and the night variants).
     */
    fun icon(): Int? {
        val c = code
        val t = Math.round(prefs.getFloat("temp_c", 0f))
        return when {
            c < 0 -> null
            c <= 1 && t >= 35 -> R.drawable.n9_weather_hot
            c <= 1 && t <= -12 -> R.drawable.n9_weather_cold
            c <= 1 -> if (isDay) R.drawable.n9_weather_sunny else R.drawable.n9_weather_clear
            c == 2 -> if (isDay) R.drawable.n9_weather_partly_sunny else R.drawable.n9_weather_partly_cloudy
            c == 3 -> if (isDay) R.drawable.n9_weather_cloudy else R.drawable.n9_weather_mostly_cloudy
            c == 45 || c == 48 -> R.drawable.n9_weather_foggy
            c in 51..55 || c == 61 -> R.drawable.n9_weather_shower
            c == 56 || c == 57 || c == 66 || c == 67 -> R.drawable.n9_weather_freezing_rain
            c == 63 || c == 65 -> R.drawable.n9_weather_rain
            c == 71 || c == 73 || c == 77 -> R.drawable.n9_weather_flurries
            c == 75 -> R.drawable.n9_weather_snow
            c == 80 -> if (isDay) R.drawable.n9_weather_sunny_shower else R.drawable.n9_weather_shower
            c == 81 || c == 82 -> R.drawable.n9_weather_shower
            c == 85 -> if (isDay) R.drawable.n9_weather_sunny_flurries else R.drawable.n9_weather_flurries
            c == 86 -> R.drawable.n9_weather_snow
            c == 95 -> if (isDay) R.drawable.n9_weather_sunny_thunder else R.drawable.n9_weather_shower_lightning
            c == 96 || c == 99 -> R.drawable.n9_weather_stormy
            else -> R.drawable.n9_weather_cloudy
        }
    }

    /** Tapping the weather opens a weather app, as the N9's opened AccuWeather. */
    fun openApp(ctx: Context) {
        val pm = ctx.packageManager
        val pkgs = listOf("com.sec.android.daemonapp", "com.google.android.apps.weather", "com.accuweather.android",
            "com.weather.Weather", "org.breezyweather", "co.ronaldo.weather")
        for (p in pkgs) pm.getLaunchIntentForPackage(p)?.let {
            runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onSuccess { return }
        }
    }
}
