package dev.modi.coverwallpaper

import android.graphics.Bitmap
import java.util.concurrent.CopyOnWriteArraySet

/** Shared "what's playing right now" state, written by the listener and read by the wallpaper. */
object NowPlaying {
    data class Track(
        val art: Bitmap? = null,
        val title: String? = null,
        val artist: String? = null,
        val playing: Boolean = false,
    )

    @Volatile
    var current: Track = Track()
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun update(track: Track) {
        current = track
        listeners.forEach { it() }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
}
