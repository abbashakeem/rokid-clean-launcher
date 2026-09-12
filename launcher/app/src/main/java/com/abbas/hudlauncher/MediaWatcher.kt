package com.abbas.hudlauncher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log

data class NowPlaying(val title: String, val artist: String, val playing: Boolean, val positionMs: Long, val durationMs: Long)

/**
 * Follows the active media session (phone music arrives over Bluetooth as
 * com.android.bluetooth/BluetoothMediaBrowserService). Needs notification-listener access.
 */
class MediaWatcher(private val context: Context, private val onChange: (NowPlaying?) -> Unit) {
    private val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, HudNotificationListener::class.java)
    private var controller: MediaController? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { controller = null; publish() }
    }

    val available: Boolean get() = try { msm.getActiveSessions(listenerComponent); true } catch (e: SecurityException) { false }

    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            pick(msm.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification-listener access; music widget disabled")
            onChange(null)
        }
    }

    fun stop() {
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(callback); controller = null
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun refresh() = publish()

    private fun pick(sessions: List<MediaController>?) {
        controller?.unregisterCallback(callback)
        controller = sessions?.firstOrNull { it.metadata != null } ?: sessions?.firstOrNull()
        controller?.registerCallback(callback)
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        val st = c?.playbackState
        if (c == null || md == null || st == null || st.state == PlaybackState.STATE_NONE || st.state == PlaybackState.STATE_STOPPED) {
            onChange(null); return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.description?.title?.toString() ?: return onChange(null)
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        onChange(NowPlaying(title, artist, st.state == PlaybackState.STATE_PLAYING, st.position, md.getLong(MediaMetadata.METADATA_KEY_DURATION)))
    }

    companion object { private const val TAG = "Media" }
}
