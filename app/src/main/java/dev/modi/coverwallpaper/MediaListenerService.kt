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
 * so this listener exists purely to watch the active player (Apple Music first).
 */
class MediaListenerService : NotificationListenerService() {

    companion object {
        const val APPLE_MUSIC = "com.apple.android.music"
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var lastArtKey: String? = null
    private var lastArt: Bitmap? = null

    // Full-resolution cover from Apple's catalogue
    private var hiResRequested: String? = null
    private var hiResFor: String? = null
    private var hiResArt: Bitmap? = null
    private var hiResAlbum: String? = null
    private var hiResArtist: String? = null

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
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)

        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val key = "$title|$artist|$album"

        if (title != null && key != hiResRequested) {
            hiResRequested = key
            fetchHiRes(key, artist, album, title)
        }
        // Once the full-resolution cover has arrived for this track, always use it.
        if (key == hiResFor && hiResArt != null) {
            NowPlaying.update(NowPlaying.Track(hiResArt, title, artist, playing))
            return
        }

        when {
            bmp != null -> {
                lastArtKey = key; lastArt = bmp
                NowPlaying.update(NowPlaying.Track(bmp, title, artist, playing))
            }
            key == lastArtKey -> {
                NowPlaying.update(NowPlaying.Track(lastArt, title, artist, playing))
            }
            else -> {
                // Some players only give a URI for the art — load it off the main thread.
                val uri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                NowPlaying.update(NowPlaying.Track(lastArt, title, artist, playing))
                if (uri != null) loadUri(uri, key, title, artist, playing)
            }
        }
    }

    private fun fetchHiRes(key: String, artist: String?, album: String?, title: String?) {
        // Same album as the cover we already have in full resolution? Reuse it.
        if (album != null && album == hiResAlbum && artist == hiResArtist && hiResArt != null) {
            hiResFor = key
            return
        }
        io.execute {
            val bmp = HiResArt.fetch(artist, album, title) ?: return@execute
            main.post {
                if (hiResRequested != key) return@post   // song changed while downloading
                hiResFor = key; hiResArt = bmp
                hiResAlbum = album; hiResArtist = artist
                push()
            }
        }
    }

    private fun loadUri(uri: String, key: String, title: String?, artist: String?, playing: Boolean) {
        io.execute {
            val bmp = try {
                contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null } ?: return@execute
            main.post {
                lastArtKey = key; lastArt = bmp
                NowPlaying.update(NowPlaying.Track(bmp, title, artist, playing))
            }
        }
    }
}
