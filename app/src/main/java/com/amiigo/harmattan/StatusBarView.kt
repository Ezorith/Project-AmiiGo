package com.amiigo.harmattan

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import java.util.Date
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The Harmattan status area (sysuid statusarea.css): 36px tall, black.
 * Left: battery, phone signal, connectivity; right: clock (FONT_SMALL, white, "%R").
 * Tapping opened the N9 status menu — here that is the system quick settings;
 * pulling down opens the notification shade.
 */
class StatusBarView(ctx: Context) : View(ctx) {

    private val main = Handler(Looper.getMainLooper())
    private val clockPaint = N9.textPaint(N9.regular, N9.FONT_XSMALL, N9.COLOR_INVERTED_FOREGROUND, scaled = false)
        .apply { textSize *= Apps.statusBarScale.coerceIn(0.7f, 1.6f) }
    private val iconPaint = Apps.iconPaint()
    private val bgPaint = android.graphics.Paint()

    private var level = 100
    private var charging = false
    private var chargeFrame = 0
    /** System signal level 0..4; NO_SIM / AIRPLANE / NO_PHONE otherwise. */
    private var signal = NO_PHONE
    private var operator = ""
    private var wifi = false
    private var wifiActiveUntil = 0L
    private var lastBytes = -1L
    private val opPaint = N9.textPaint(N9.regular, N9.FONT_XSMALL, N9.COLOR_INVERTED_FOREGROUND, scaled = false)
        .apply { textSize *= Apps.statusBarScale.coerceIn(0.7f, 1.6f) }

    companion object {
        const val NO_PHONE = -1
        const val NO_SIM = -2
        const val AIRPLANE = -3
        /** How long the Wi-Fi icon stays blue after traffic is seen. */
        const val ACTIVITY_HOLD_MS = 700L
        /** Height every status icon's visible shape is drawn at (N9 px), and the gap between icons. */
        const val ICON_H = 15f
        const val GAP = 6f
    }
    private var silent = false
    private var alarm = false
    var notifier = false
        set(v) { field = v; invalidate() }

    /**
     * The display cutout band at the top (its safe inset) and the camera hole's bounds. The bar fills
     * the band, its icons centred on the hole and kept clear of it, like a system status bar.
     */
    var bandHeight = 0
        set(v) { field = v; requestLayout() }
    var hole: android.graphics.Rect? = null
        set(v) { field = v; invalidate() }

    /** The screen's top corner radius (px); the bar's ends move in so they clear the rounded corners. */
    var cornerRadius = 0
        set(v) { field = v; invalidate() }

    /** Space at each end of the bar: the N9's 8px, or more on screens with rounded corners. */
    private val sidePad: Float get() = maxOf(px(8f), cornerRadius * 0.6f)

    /** The bar's height: the N9's 36px, or the cutout band if that's taller. */
    val barHeight: Int get() = maxOf(ipx(N9.STATUSBAR), bandHeight)

    /** The status bar's own size (Home settings), on top of the N9's measures. */
    private val k = Apps.statusBarScale.coerceIn(0.7f, 1.6f)
    private fun px(v: Float) = N9.px(v) * k
    private fun px(v: Int) = N9.px(v) * k
    private fun ipx(v: Float) = (N9.px(v) * k + 0.5f).toInt()
    private fun ipx(v: Int) = (N9.px(v) * k + 0.5f).toInt()

    private fun bmp(res: Int): Bitmap = N9.bitmap(context, res)

    private val inkCache = HashMap<Long, android.graphics.Rect>()

    /** The part of an icon that isn't transparent (from column [fromX] on). */
    private fun ink(res: Int, fromX: Int = 0): android.graphics.Rect = inkCache.getOrPut(res.toLong() shl 8 or fromX.toLong()) {
        val b = bmp(res)
        var l = b.width; var t = b.height; var r = -1; var btm = -1
        for (y in 0 until b.height) for (x in fromX until b.width) if ((b.getPixel(x, y) ushr 24) > 40) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > btm) btm = y
        }
        if (r < 0) android.graphics.Rect(fromX, 0, b.width, b.height) else android.graphics.Rect(l, t, r + 1, btm + 1)
    }

    private val battery = intArrayOf(
        R.drawable.n9_status_battery1, R.drawable.n9_status_battery2, R.drawable.n9_status_battery3, R.drawable.n9_status_battery4,
        R.drawable.n9_status_battery5, R.drawable.n9_status_battery6, R.drawable.n9_status_battery7, R.drawable.n9_status_battery8,
    )
    private val network = intArrayOf(
        R.drawable.n9_status_network0, R.drawable.n9_status_network1, R.drawable.n9_status_network2,
        R.drawable.n9_status_network3, R.drawable.n9_status_network4, R.drawable.n9_status_network5,
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeight)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
                    val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    level = l * 100 / sc
                    val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
                    charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> readRinger()
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> readAlarm()
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> readSignal()
            }
            invalidate()
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { main.post { readNet() } }
        override fun onLost(network: Network) { main.post { readNet() } }
    }

    private val tick = object : Runnable {
        override fun run() {
            readSignal()
            if (charging) chargeFrame = (chargeFrame + 1) % 9
            invalidate()
            // Charging animation steps every 500ms; otherwise refresh on the minute.
            val delay = if (charging) 500L else 60_000L - System.currentTimeMillis() % 60_000L
            main.postDelayed(this, delay)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, f)
        runCatching { context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback) }
        listenSignal(true)
        readRinger(); readAlarm(); readNet(); readSignal()
        main.post(tick)
        main.post(traffic)
    }

    /** Wi-Fi activity: blue only while data is actually moving (sampled twice a second). */
    private val traffic = object : Runnable {
        override fun run() {
            if (wifi) {
                val b = android.net.TrafficStats.getTotalRxBytes() + android.net.TrafficStats.getTotalTxBytes()
                val now = android.os.SystemClock.uptimeMillis()
                val wasActive = now < wifiActiveUntil
                if (lastBytes >= 0 && b - lastBytes > 2048) wifiActiveUntil = now + ACTIVITY_HOLD_MS
                lastBytes = b
                if (wasActive != (now < wifiActiveUntil)) invalidate()
            } else lastBytes = -1L
            main.postDelayed(this, 500)
        }
    }

    // Live signal strength (no permission needed for signal strength updates).
    private var signalCallback: Any? = null

    @Suppress("DEPRECATION")
    private fun listenSignal(on: Boolean) {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val cb = object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.SignalStrengthsListener {
                        override fun onSignalStrengthsChanged(s: android.telephony.SignalStrength) { main.post { readSignal(); invalidate() } }
                    }
                    tm.registerTelephonyCallback(context.mainExecutor, cb)
                    signalCallback = cb
                } else (signalCallback as? android.telephony.TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
            } else {
                if (on) {
                    val l = object : android.telephony.PhoneStateListener() {
                        override fun onSignalStrengthsChanged(s: android.telephony.SignalStrength) { readSignal(); invalidate() }
                    }
                    tm.listen(l, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
                    signalCallback = l
                } else (signalCallback as? android.telephony.PhoneStateListener)?.let { tm.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE) }
            }
        }
        if (!on) signalCallback = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        listenSignal(false)
        main.removeCallbacks(tick)
        main.removeCallbacks(traffic)
    }

    fun refresh() { readSignal(); readNet(); invalidate() }

    private fun readRinger() {
        silent = context.getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL
    }

    private fun readAlarm() {
        alarm = context.getSystemService(AlarmManager::class.java).nextAlarmClock != null
    }

    private fun readNet() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        invalidate()
    }

    /** The phone line the system status bar shows: the default data subscription's. */
    private fun phone(): TelephonyManager? {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        val sub = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
        return if (sub != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) runCatching { tm.createForSubscriptionId(sub) }.getOrDefault(tm) else tm
    }

    private fun readSignal() {
        val airplane = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val tm = phone()
        operator = ""
        signal = when {
            airplane -> AIRPLANE
            tm == null || tm.phoneType == TelephonyManager.PHONE_TYPE_NONE -> NO_PHONE
            tm.simState != TelephonyManager.SIM_STATE_READY -> NO_SIM
            // SignalStrength.getLevel(): the same 0..4 level the system's own status bar draws.
            else -> (runCatching { tm.signalStrength?.level }.getOrNull() ?: 0).coerceIn(0, 4)
        }
        if (signal >= 0) operator = (runCatching { tm?.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { tm?.simOperatorName }.getOrNull()).orEmpty().trim()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        // Its own area only: drawColor would fill whatever the parent lets it draw on, and home
        // doesn't clip its children (the Home editor's side panels), so it blacked out the screen.
        bgPaint.color = N9.COLOR_INVERTED_BACKGROUND
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        // Centre the row on the camera hole when there is one, so icons line up with it.
        val cut = hole?.takeIf { it.height() > 0 && it.top < height }
        val h = px(N9.STATUSBAR)
        val mid = cut?.exactCenterY() ?: (height / 2f)
        val top = mid - h / 2f
        // Space the hole takes in the row (plus a gap); a hole in a corner pushes that side's items in.
        val gap = px(8f)
        val holeL = cut?.let { it.left - gap } ?: Float.MAX_VALUE
        val holeR = cut?.let { it.right + gap } ?: -1f
        val holeLeftSide = cut != null && cut.right < width / 3
        val holeRightSide = cut != null && cut.left > width * 2 / 3
        // Every icon is drawn cropped to its visible pixels at one height, so the art's different
        // built-in padding (the Wi-Fi fan has more than the battery or bars) doesn't change its size.
        val iconH = px(ICON_H)
        val iy = top + (h - iconH) / 2f
        fun drawCrop(b: Bitmap, ink: android.graphics.Rect, x: Float): Float {
            val w = iconH * ink.width() / ink.height()
            canvas.drawBitmap(b, ink, android.graphics.RectF(x, iy, x + w, iy + iconH), iconPaint)
            return x + w
        }
        fun draw(res: Int, x: Float): Float = drawCrop(bmp(res), ink(res), x)

        var x = if (holeLeftSide) holeR else sidePad
        // Left items stop at a centred hole.
        val leftLimit = if (cut != null && !holeLeftSide && !holeRightSide) holeL else width / 2f
        // Battery: 8 steps; low/very-low variants below 12%/5%. Charging cycles through the steps.
        val bres = when {
            charging -> battery[(ceil(level / 12.5f).toInt().coerceIn(1, 8) - 1 + chargeFrame) % 8]
            level <= 5 -> R.drawable.n9_status_battery_verylow
            level <= 12 -> R.drawable.n9_status_battery_low
            else -> battery[(ceil(level / 12.5f).toInt().coerceIn(1, 8)) - 1]
        }
        x = drawCrop(bmp(bres), ink(battery[7]), x) + px(GAP)
        when (signal) {
            AIRPLANE -> x = draw(R.drawable.n9_status_flight_mode, x) + px(GAP)
            NO_SIM -> x = draw(R.drawable.n9_status_no_simcard, x) + px(GAP)
            NO_PHONE -> {}
            else -> {
                // The system has 4 bars; the N9 icon has 5. Dropping the N9's first (shortest) bar gives
                // a 4-bar N9 icon whose lit bars are exactly the system's level: network(L+1) minus bar 1.
                val res = network[signal + 1]
                x = drawCrop(bmp(res), ink(network[5], fromX = 4 * N9.HD), x) + px(GAP)
                // Demo mode (screenshots): a stand-in network name rather than the phone's.
                val operator = if (Demo.items != null) "Harmattan" else operator
                if (operator.isNotEmpty() && Apps.showOperator) {
                    // Shortened if it would run into the hole (leaving room for the Wi-Fi icon).
                    val wifiW = if (wifi) iconH * ink(R.drawable.n9_status_wlan).width() / ink(R.drawable.n9_status_wlan).height() + px(GAP) else 0f
                    val room = leftLimit - x - wifiW - px(8f)
                    val name = android.text.TextUtils.ellipsize(operator, android.text.TextPaint(opPaint), room, android.text.TextUtils.TruncateAt.END).toString()
                    if (name.isNotEmpty()) {
                        val ofm = opPaint.fontMetrics
                        canvas.drawText(name, x, top + h / 2f - (ofm.ascent + ofm.descent) / 2f, opPaint)
                        x += opPaint.measureText(name) + px(8f)
                    }
                }
            }
        }
        // Wi-Fi: white like the other icons; blue (the N9's "-active" art) only while data moves.
        if (wifi) x = draw(if (android.os.SystemClock.uptimeMillis() < wifiActiveUntil) R.drawable.n9_status_wlan_active else R.drawable.n9_status_wlan, x) + px(GAP)

        // Right side, as on the N9: the notification flag and other indicators sit just left of the clock.
        val time = DateFormat.getTimeFormat(context).format(Date())
            .replace(" AM", "").replace(" PM", "").replace(" am", "").replace(" pm", "")
        val tw = clockPaint.measureText(time)
        val fm = clockPaint.fontMetrics
        val baseline = top + h / 2f - (fm.ascent + fm.descent) / 2f
        var rx = (if (holeRightSide) holeL else width - sidePad) - tw
        canvas.drawText(time, rx, baseline, clockPaint)
        fun drawLeftOf(res: Int) {
            val ink = ink(res)
            val w = iconH * ink.width() / ink.height()
            // Right items stop at a centred hole.
            if (cut != null && !holeLeftSide && !holeRightSide && rx - px(GAP) - w < holeR) return
            rx -= px(GAP) + w
            drawCrop(bmp(res), ink, rx)
        }
        if (alarm) drawLeftOf(R.drawable.n9_status_alarm)
        if (silent) drawLeftOf(R.drawable.n9_status_silent)
        if (notifier) drawLeftOf(R.drawable.n9_status_notifier)
    }

    // ---- gestures ----
    private var downY = 0f
    private var opened = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; opened = false }
            MotionEvent.ACTION_MOVE -> if (!opened && e.y - downY > px(20f)) { opened = true; expand(false) }
            MotionEvent.ACTION_UP -> if (!opened && abs(e.y - downY) < px(20f)) expand(true)
        }
        return true
    }

    @SuppressLint("WrongConstant")
    private fun expand(settings: Boolean) {
        runCatching {
            val sb = context.getSystemService("statusbar")
            val cls = Class.forName("android.app.StatusBarManager")
            cls.getMethod(if (settings) "expandSettingsPanel" else "expandNotificationsPanel").invoke(sb)
        }
    }
}
