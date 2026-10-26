package dev.modi.coverwallpaper

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import java.util.concurrent.Executors

/**
 * Android only lets apps with notification access see other apps' media sessions,
 * so this listener exists purely to watch the active player (Apple Music first)
 * and look up its album in Apple's catalogue.
 */
class MediaListenerService : NotificationListenerService() {

    companion object {
        const val APPLE_MUSIC = "com.apple.android.music"
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null

    // Small cover straight from the player, used until the full-res one arrives
    private var sessionArtKey: String? = null
    private var sessionArt: Bitmap? = null
    private var uriRequested: String? = null

    // Apple catalogue result for the current album
    private var lookupKey: String? = null
    private var infoKey: String? = null
    private var info: AppleCatalog.Info? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onSessionDestroyed() {
            controller = null
            pick(activeSessions())
        }
    }

    private fun component() = ComponentName(this, MediaListenerService::class.java)

    private fun activeSessions(): List<MediaController>? =
        try { msm?.getActiveSessions(component()) } catch (e: SecurityException) { null }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, component(), main)
        } catch (_: SecurityException) { }
        pick(activeSessions())
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) { }
        controller?.unregisterCallback(callback)
        controller = null
        NowPlaying.update(NowPlaying.Track())
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** Prefer Apple Music; fall back to whatever else is playing. */
    private fun pick(list: List<MediaController>?) {
        val chosen = list?.firstOrNull { it.packageName == APPLE_MUSIC }
            ?: list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()

        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = chosen
            chosen?.registerCallback(callback, main)
        }
        push()
    }

    private fun push() {
        val c = controller
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (c == null || title == null) {
            NowPlaying.update(NowPlaying.Track())
            return
        }
        val playing = c.playbackState?.state.let {
            it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING
        }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val albumKey = "$artist|${album ?: title}"

        // Small cover from the player
        val small = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (small != null) {
            sessionArtKey = albumKey; sessionArt = small
        } else if (sessionArtKey != albumKey) {
            val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            if (uri != null && uri != uriRequested) { uriRequested = uri; loadUri(uri, albumKey) }
        }

        // Full-res cover + motion art from Apple, once per album
        if (albumKey != lookupKey) {
            lookupKey = albumKey
            io.execute {
                AppleCatalog.lookup(artist, album, title) { result ->
                    main.post {
                        if (lookupKey == albumKey) { infoKey = albumKey; info = result; push() }
                    }
                }
            }
        }
        val i = if (infoKey == albumKey) info else null
        val art = i?.art ?: if (sessionArtKey == albumKey) sessionArt else null

        NowPlaying.update(
            NowPlaying.Track(
                title = title,
                artist = artist,
                playing = playing,
                art = art,
                motionUrl = i?.motionUrl,
                motionPreview = i?.motionPreview,
            )
        )
    }

    private fun loadUri(uri: String, key: String) {
        io.execute {
            val bmp = try {
                contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null } ?: return@execute
            main.post {
                sessionArtKey = key; sessionArt = bmp
                push()
            }
        }
    }
}
