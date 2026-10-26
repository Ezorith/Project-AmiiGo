package com.amiigo.harmattan

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * The system's own recent-apps list, through Shizuku. Android only lets privileged code read recent
 * tasks of other apps or remove them; Shizuku (an app the user runs) provides shell-level access, so
 * the switcher can show exactly what the system Recents shows, and closing a tile removes the task
 * there too. Without Shizuku the switcher falls back to [Apps]' own tracking.
 */
object SystemRecents {
    class Task(val id: Int, val component: ComponentName)

    const val REQUEST_CODE = 0x5E1
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val RECENT_IGNORE_UNAVAILABLE = 0x0002

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0) }.isSuccess
    fun running() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun granted() = running() && runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun request() { if (running()) runCatching { Shizuku.requestPermission(REQUEST_CODE) } }

    fun onPermissionResult(l: () -> Unit) {
        runCatching { Shizuku.addRequestPermissionResultListener { _, _ -> l() } }
        runCatching { Shizuku.addBinderReceivedListener { l() } }
    }

    private var atm: Any? = null

    /** IActivityTaskManager, talking to system_server through Shizuku's shell-identity binder. */
    private fun atm(): Any {
        atm?.let { return it }
        if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("")
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity_task"))
        val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!.also { atm = it }
    }

    /** Recent tasks, most recent first, as the system Recents screen lists them. Blocking (binder call). */
    fun tasks(): List<Task>? = runCatching {
        val a = atm()
        val m = a.javaClass.getMethod("getRecentTasks", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val slice = m.invoke(a, 50, RECENT_IGNORE_UNAVAILABLE, Process.myUserHandle().hashCode())!!
        val list = slice.javaClass.getMethod("getList").invoke(slice) as List<*>
        list.mapNotNull { o ->
            val info = o as? ActivityManager.RecentTaskInfo ?: return@mapNotNull null
            val base = info.baseIntent
            if (base?.hasCategory(Intent.CATEGORY_HOME) == true) return@mapNotNull null
            val comp = base?.component ?: info.baseActivity ?: return@mapNotNull null
            Task(info.taskId, comp)
        }
    }.onFailure { atm = null }.getOrNull()

    /**
     * Android's snapshot of a task (what the system Recents shows), scaled to at most [maxW] wide and
     * cropped below the status bar, or null. Needs READ_FRAME_BUFFER, which Shizuku's shell identity has.
     * Blocking (binder call).
     */
    fun snapshot(taskId: Int, maxW: Int): android.graphics.Bitmap? = runCatching {
        val target = snapshotService(atm())
        val m = target.javaClass.methods.filter { it.name == "getTaskSnapshot" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType }
            .minByOrNull { it.parameterTypes.size } ?: return null
        // Up to Android 16: (taskId, isLowResolution[, takeSnapshotIfNeeded]) -> full resolution, never force a capture.
        // Android 17+: (taskId, latestCaptureTime, resolution) -> any snapshot (0), TaskSnapshotManager.RESOLUTION_ANY (3).
        val args = arrayOfNulls<Any>(m.parameterTypes.size)
        args[0] = taskId
        for (i in 1 until args.size) args[i] = when (m.parameterTypes[i]) {
            Boolean::class.javaPrimitiveType -> false
            Long::class.javaPrimitiveType -> 0L
            Int::class.javaPrimitiveType -> 3
            else -> null
        }
        val snap = m.invoke(target, *args) ?: return null
        val c = snap.javaClass
        val insets = runCatching { c.getMethod("getContentInsets").invoke(snap) as? android.graphics.Rect }.getOrNull()
        // Android 17 hands the picture out through wrapToBitmap(); earlier versions as a HardwareBuffer.
        var hb: android.hardware.HardwareBuffer? = null
        val hw: android.graphics.Bitmap = runCatching { c.getMethod("wrapToBitmap").invoke(snap) as? android.graphics.Bitmap }.getOrNull()
            ?: run {
                hb = c.getMethod("getHardwareBuffer").invoke(snap) as? android.hardware.HardwareBuffer ?: return null
                val cs = runCatching { c.getMethod("getColorSpace").invoke(snap) as? android.graphics.ColorSpace }.getOrNull()
                android.graphics.Bitmap.wrapHardwareBuffer(hb!!, cs)
            } ?: return null
        try {
            // A hardware bitmap can't be drawn on a software canvas: copy it to memory first.
            val sw = if (hw.config == android.graphics.Bitmap.Config.HARDWARE) hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return null else hw
            val top = (insets?.top ?: 0).coerceIn(0, sw.height / 4)
            val w = sw.width
            val h = sw.height - top
            val k = minOf(1f, maxW.toFloat() / w)
            val out = android.graphics.Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).drawBitmap(sw, android.graphics.Rect(0, top, w, sw.height),
                android.graphics.Rect(0, 0, out.width, out.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            if (sw !== hw) sw.recycle()
            out
        } finally {
            hw.recycle()
            hb?.close()
            runCatching { c.getMethod("closeBuffer").invoke(snap) }
        }
    }.onFailure { snapshots = null; android.util.Log.w("Harmattan", "snapshot($taskId) failed", it) }.getOrNull()

    private var snapshots: Any? = null

    /**
     * Where getTaskSnapshot lives: IActivityTaskManager itself up to Android 16, its own
     * ITaskSnapshotManager after. That binder is wrapped for Shizuku too, so calls keep the shell identity.
     */
    private fun snapshotService(a: Any): Any {
        if (a.javaClass.methods.any { it.name == "getTaskSnapshot" }) return a
        snapshots?.let { return it }
        val mgr = a.javaClass.getMethod("getTaskSnapshotManager").invoke(a)!!
        val binder = mgr.javaClass.getMethod("asBinder").invoke(mgr) as IBinder
        val stub = Class.forName("android.window.ITaskSnapshotManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(binder))!!.also { snapshots = it }
    }

    /** Remove a task from the system Recents (this also ends its process if nothing else uses it). */
    fun remove(taskId: Int): Boolean = runCatching {
        val a = atm()
        a.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(a, taskId) as Boolean
    }.onFailure { atm = null }.getOrDefault(false)
}
