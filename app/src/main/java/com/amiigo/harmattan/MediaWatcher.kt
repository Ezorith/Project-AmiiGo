package com.amiigo.harmattan

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * What's playing, straight from the active media session (not the notification), so title, cover
 * art, position and duration are the player's own and stay current. Needs notification access.
 */
object MediaWatcher {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private var msm: MediaSessionManager? = null
    var controller: MediaController? = null
        private set

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        msm = app.getSystemService(MediaSessionManager::class.java)
        attach()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    private fun changed() = main.post { listeners.forEach { it() } }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    /** (Re)connect; call again once notification access is granted. */
    fun attach() {
        val m = msm ?: return
        val cn = ComponentName(app, EventsListenerService::class.java)
        runCatching {
            m.removeOnActiveSessionsChangedListener(sessionsListener)
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            pick(m.getActiveSessions(cn))
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { changed() }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Another session may have started playing; re-pick, then redraw.
            runCatching { msm?.getActiveSessions(ComponentName(app, EventsListenerService::class.java)) }.getOrNull()
                ?.let { pick(it) } ?: changed()
        }
        override fun onSessionDestroyed() { attach() }
    }

    /** Prefer whatever is playing; otherwise the most recent session that has a track loaded. */
    private fun pick(list: List<MediaController>) {
        val withTrack = list.filter { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
        val best = withTrack.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: withTrack.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        changed()
    }

    // ---- what the row shows ----

    val title: String? get() = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
    val artist: String? get() = controller?.metadata?.let {
        it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM)
    }
    val art: Bitmap? get() = controller?.metadata?.let {
        it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }
    val pkg: String? get() = controller?.packageName
    val playing: Boolean get() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
    val duration: Long get() = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    /** Live position: the last reported one, advanced by the time since it was reported. */
    val position: Long get() {
        val s = controller?.playbackState ?: return 0L
        var p = s.position
        if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0) {
            p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        val d = duration
        return if (d > 0) p.coerceIn(0, d) else p.coerceAtLeast(0)
    }

    fun playPause() { val t = controller?.transportControls ?: return; if (playing) t.pause() else t.play() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }

    fun open() {
        val c = controller ?: return
        val pi = c.sessionActivity
        if (pi != null && EventStore.sendPending(app, pi)) return
        app.packageManager.getLaunchIntentForPackage(c.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { app.startActivity(it) } }
    }
}
