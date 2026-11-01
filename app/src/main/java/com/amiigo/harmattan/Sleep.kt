package com.amiigo.harmattan

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Double tap to sleep: turns the screen off, so the phone locks as with the power key (fingerprint
 * and face unlock keep working). Through Shizuku when it's allowed, else through Harmattan Home's
 * accessibility service (Android 9+), which the user switches on once.
 */
object Sleep {
    fun enabled(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("double_tap_sleep", false)
    fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("double_tap_sleep", on).apply()

    /** Whether the screen can be turned off right now (Shizuku allowed, or the service on). */
    fun ready(ctx: Context) = SystemRecents.granted() || serviceOn(ctx)

    fun serviceOn(ctx: Context): Boolean {
        val me = ComponentName(ctx, SleepService::class.java).flattenToString()
        return Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.equals(me, ignoreCase = true) } == true
    }

    fun now(ctx: Context): Boolean {
        if (SystemRecents.granted() && viaShizuku()) return true
        return SleepService.instance?.lock() == true
    }

    /** IPowerManager.goToSleep as the shell user (it holds DEVICE_POWER). */
    private fun viaShizuku(): Boolean = runCatching {
        val binder: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("power"))
        val pm = Class.forName("android.os.IPowerManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        val m = pm.javaClass.methods.first { it.name == "goToSleep" }
        // goToSleep(long eventTime, int reason, int flags); reason 4 = power button.
        if (m.parameterTypes.size == 3) m.invoke(pm, SystemClock.uptimeMillis(), 4, 0)
        else m.invoke(pm, SystemClock.uptimeMillis(), 4, 0, 0)
        true
    }.getOrDefault(false)
}

/** Only used to turn the screen off (Settings → Accessibility → Harmattan Home). Reads nothing. */
class SleepService : AccessibilityService() {
    companion object { @Volatile var instance: SleepService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun lock(): Boolean = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
}
