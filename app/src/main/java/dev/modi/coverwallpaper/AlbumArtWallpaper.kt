package dev.modi.coverwallpaper

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Full-screen album cover that slowly drifts and breathes while music plays,
 * cross-fading when the track changes. Freezes (no redraws) when paused or hidden.
 */
class AlbumArtWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = ArtEngine()

    inner class ArtEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val shadePaint = Paint()
        private val idlePaint = Paint()
        private val dst = RectF()

        private var visible = false
        private var width = 0
        private var height = 0

        private var art: Bitmap? = null
        private var prevArt: Bitmap? = null
        private var fadeStart = 0L
        private var playing = false

        // Animation clock that only advances while music is playing,
        // so pausing freezes the image in place instead of jumping.
        private var animTime = 0f
        private var lastFrame = 0L

        private val onTrack: () -> Unit = { handler.post { applyTrack(fade = true) } }

        private val frame = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible && needsAnimation()) handler.postDelayed(this, FRAME_MS)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            NowPlaying.addListener(onTrack)
            // Make sure the media listener is running (e.g. after an app update).
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(this@AlbumArtWallpaper, MediaListenerService::class.java)
                )
            } catch (_: Exception) { }
            applyTrack(fade = false)
        }

        override fun onDestroy() {
            NowPlaying.removeListener(onTrack)
            handler.removeCallbacks(frame)
            super.onDestroy()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                lastFrame = SystemClock.uptimeMillis()
                schedule()
            } else {
                handler.removeCallbacks(frame)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w; height = h
            // Darken top and bottom a little so the clock and notifications stay readable.
            shadePaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0x66000000, 0x00000000, 0x00000000, 0x80000000.toInt()),
                floatArrayOf(0f, 0.3f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            idlePaint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                Color.rgb(28, 27, 46), Color.rgb(70, 18, 40),
                Shader.TileMode.CLAMP
            )
            schedule()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        private fun applyTrack(fade: Boolean) {
            val t = NowPlaying.current
            if (t.art !== art) {
                prevArt = if (fade) art else null
                art = t.art
                fadeStart = SystemClock.uptimeMillis()
            }
            playing = t.playing
            schedule()
        }

        private fun fading() = prevArt != null &&
            SystemClock.uptimeMillis() - fadeStart < FADE_MS

        private fun needsAnimation() = (playing && art != null) || fading()

        private fun schedule() {
            handler.removeCallbacks(frame)
            if (visible && width > 0) handler.post(frame)
        }

        private fun drawFrame() {
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastFrame).coerceIn(0, 100)) / 1000f
            lastFrame = now
            if (playing) animTime += dt

            val holder = surfaceHolder
            val canvas: Canvas = try {
                holder.lockHardwareCanvas()
            } catch (_: Exception) {
                null
            } ?: return

            try {
                canvas.drawColor(Color.BLACK)
                val f = ((now - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                val current = art

                prevArt?.let { if (f < 1f) drawArt(canvas, it, 255) }
                if (current != null) {
                    drawArt(canvas, current, (255 * f).toInt())
                } else {
                    idlePaint.alpha = (255 * f).toInt().coerceAtLeast(if (prevArt == null) 255 else 0)
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), idlePaint)
                }
                if (f >= 1f) prevArt = null

                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        /** Centre-crop to fill the screen, then slowly pan and zoom across the cover. */
        private fun drawArt(canvas: Canvas, b: Bitmap, alpha: Int) {
            if (b.isRecycled || b.width == 0 || b.height == 0) return
            val t = animTime
            val base = max(width / b.width.toFloat(), height / b.height.toFloat())
            val zoom = 1.08f + 0.05f * sin(2 * PI.toFloat() * t / ZOOM_PERIOD)
            val s = base * zoom
            val dw = b.width * s
            val dh = b.height * s
            val px = 0.5f + 0.5f * sin(2 * PI.toFloat() * t / PAN_X_PERIOD)
            val py = 0.5f + 0.5f * sin(2 * PI.toFloat() * t / PAN_Y_PERIOD + 1f)
            val left = -(dw - width) * px
            val top = -(dh - height) * py
            dst.set(left, top, left + dw, top + dh)
            artPaint.alpha = alpha
            canvas.drawBitmap(b, null, dst, artPaint)
        }
    }

    companion object {
        private const val FRAME_MS = 33L        // ~30 fps
        private const val FADE_MS = 900L
        private const val ZOOM_PERIOD = 22f     // seconds
        private const val PAN_X_PERIOD = 40f
        private const val PAN_Y_PERIOD = 29f
    }
}
