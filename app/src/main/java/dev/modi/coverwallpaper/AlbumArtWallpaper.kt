package dev.modi.coverwallpaper

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import kotlin.math.abs
import kotlin.math.exp

/**
 * What it shows:
 *  - music playing, album has motion art  → the looping motion video, full screen
 *  - music playing, no motion art         → the cover centred, floating over its own colours
 *  - nothing playing (or paused a while)  → your normal wallpaper
 *
 * Still scenes are drawn on a Canvas and cross-fade into each other. Motion art is
 * decoded straight onto the wallpaper surface by ExoPlayer; while it plays, nothing
 * else draws (a surface can only have one source at a time).
 */
class AlbumArtWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = ArtEngine()

    inner class ArtEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val density = resources.displayMetrics.density

        private var visible = false
        private var width = 0
        private var height = 0

        // Canvas scenes
        private var scene: Scene = IdleScene(null)
        private var sceneKey = "idle"
        private var prevScene: Scene? = null
        private var fadeStart = 0L
        private var coverCache: Pair<Bitmap, CoverScene>? = null

        // Normal wallpaper
        private var normal: Bitmap? = null
        private var normalStamp = -1L

        // Animation
        private var animTime = 0f
        private var lastFrame = 0L
        private var playing = false
        private var paused = 1f            // 0 = playing, 1 = paused (smoothed)
        private var lastPlayingAt = 0L

        // Motion art
        private var player: ExoPlayer? = null
        private var playerUrl: String? = null
        private var pendingMotion: String? = null
        private val failedUrls = HashSet<String>()

        private val onChange: () -> Unit = { handler.post { evaluate() } }
        private val onPrefs: () -> Unit = { handler.post { loadNormal(); evaluate() } }
        private val evaluateLater = Runnable { evaluate() }

        private val frame = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                val dt = (now - lastFrame).coerceIn(0, 100) / 1000f
                lastFrame = now
                if (playing) animTime += dt
                val target = if (playing) 0f else 1f
                paused += (target - paused) * (1f - exp(-dt * 5f))
                if (abs(target - paused) < 0.002f) paused = target

                var drew = true
                if (player == null) drew = drawCanvas(now)

                // Once the still frame has fully faded in, hand the surface to the video
                val pm = pendingMotion
                if (pm != null && player == null && visible && !fading(now) && drew) {
                    pendingMotion = null
                    startPlayer(pm)
                }

                // Smooth 60 fps for fades; 30 fps is plenty for the slow drift (saves battery)
                if (visible && needsFrames(now, drew)) {
                    handler.postDelayed(this, if (fading(now) || paused != target) 16L else 33L)
                }
            }
        }

        // ---------- lifecycle ----------

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            NowPlaying.addListener(onChange)
            Prefs.addListener(onPrefs)
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(this@AlbumArtWallpaper, MediaListenerService::class.java)
                )
            } catch (_: Exception) { }
            loadNormal()
        }

        override fun onDestroy() {
            NowPlaying.removeListener(onChange)
            Prefs.removeListener(onPrefs)
            handler.removeCallbacksAndMessages(null)
            releasePlayer()
            super.onDestroy()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                lastFrame = SystemClock.uptimeMillis()
                evaluate()
                player?.playWhenReady = playing
            } else {
                handler.removeCallbacks(frame)
                player?.playWhenReady = false
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            val resized = w != width || h != height
            width = w; height = h
            if (resized) loadNormal()
            schedule()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            if (player != null) {
                releasePlayer()
                pendingMotion = null
                sceneKey = ""          // force a fresh decision when the surface returns
            }
            super.onSurfaceDestroyed(holder)
        }

        // ---------- deciding what to show ----------

        private fun evaluate() {
            val t = NowPlaying.current
            val now = SystemClock.uptimeMillis()
            playing = t.playing
            if (t.playing) lastPlayingAt = now

            // Music counts as "on" while playing, and for a short grace period after pausing
            val sinceStopped = now - lastPlayingAt
            val active = t.title != null && (t.playing || (lastPlayingAt > 0 && sinceStopped < GRACE_MS))
            handler.removeCallbacks(evaluateLater)
            if (t.title != null && !t.playing && active) {
                handler.postDelayed(evaluateLater, GRACE_MS - sinceStopped + 50)
            }

            val motion = t.motionUrl?.takeIf {
                active && it !in failedUrls && Prefs.motionAllowedNow(applicationContext)
            }

            when {
                motion != null -> showMotion(motion, t.motionPreview ?: t.art)
                active && t.art != null -> { leaveMotion(); setScene("cover:${System.identityHashCode(t.art)}") { cover(t.art) } }
                else -> { leaveMotion(); setScene("idle:$normalStamp") { IdleScene(normal) } }
            }
            player?.playWhenReady = playing && visible
            schedule()
        }

        private fun showMotion(url: String, still: Bitmap?) {
            if (player != null && playerUrl == url) return
            releasePlayer()
            if (still != null) {
                setScene("still:$url:${System.identityHashCode(still)}") { FullScreenScene(still) }
            }
            pendingMotion = url
        }

        private fun leaveMotion() {
            pendingMotion = null
            if (player != null) {
                releasePlayer()
                // The canvas still holds the motion art's still frame, so we fade from that.
                fadeStart = SystemClock.uptimeMillis()
            }
        }

        private fun cover(art: Bitmap): CoverScene {
            coverCache?.let { (b, s) -> if (b === art) return s }
            return CoverScene(art, density).also { coverCache = art to it }
        }

        private inline fun setScene(key: String, make: () -> Scene) {
            if (key == sceneKey) return
            val next = make()
            prevScene = scene
            scene = next
            sceneKey = key
            fadeStart = SystemClock.uptimeMillis()
        }

        // ---------- drawing ----------

        private fun fading(now: Long) = prevScene != null && now - fadeStart < FADE_MS

        private fun needsFrames(now: Long, drew: Boolean): Boolean {
            if (player != null) return false
            if (!drew) return true                       // surface busy — try again shortly
            if (pendingMotion != null || fading(now)) return true
            val target = if (playing) 0f else 1f
            return (scene.animated && (playing || paused != target))
        }

        private fun schedule() {
            handler.removeCallbacks(frame)
            if (visible && width > 0) {
                lastFrame = SystemClock.uptimeMillis()
                handler.post(frame)
            }
        }

        /** Returns false if the surface couldn't be drawn to right now. */
        private fun drawCanvas(now: Long): Boolean {
            if (width == 0 || height == 0) return false
            val holder = surfaceHolder
            val c: Canvas = try {
                holder.lockHardwareCanvas()
            } catch (_: Exception) {
                null
            } ?: return false

            try {
                val raw = ((now - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                val f = raw * raw * (3f - 2f * raw)      // ease in-out
                val prev = prevScene
                if (prev != null && raw < 1f) {
                    prev.draw(c, width, height, animTime, paused)
                    c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * f).toInt())
                    scene.draw(c, width, height, animTime, paused)
                    c.restore()
                } else {
                    prevScene = null
                    scene.draw(c, width, height, animTime, paused)
                }
            } catch (_: Exception) {
                c.drawColor(Color.BLACK)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
            return true
        }

        // ---------- motion art ----------

        private fun startPlayer(url: String) {
            val p = ExoPlayer.Builder(applicationContext)
                .setMediaSourceFactory(HlsMediaSource.Factory(VideoCache.dataSourceFactory(applicationContext)))
                .build()
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            p.volume = 0f
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Couldn't play it — remember, and fall back to the still cover.
                    failedUrls.add(url)
                    releasePlayer()
                    sceneKey = ""
                    evaluate()
                }
            })
            p.setVideoSurface(surfaceHolder.surface)
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = playing && visible
            player = p
            playerUrl = url
        }

        private fun releasePlayer() {
            player?.let {
                try { it.clearVideoSurface() } catch (_: Exception) { }
                it.release()
            }
            player = null
            playerUrl = null
        }

        // ---------- normal wallpaper ----------

        private fun loadNormal() {
            val file = Prefs.normalWallpaperFile(applicationContext)
            val stamp = if (file.exists()) file.lastModified() else 0L
            if (stamp == normalStamp && (normal != null || stamp == 0L)) return
            normalStamp = stamp
            normal = if (stamp == 0L) null else try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (_: Throwable) { null }
        }
    }

    companion object {
        private const val FADE_MS = 700L
        private const val GRACE_MS = 15_000L         // pause this long before going back to your wallpaper
    }
}
