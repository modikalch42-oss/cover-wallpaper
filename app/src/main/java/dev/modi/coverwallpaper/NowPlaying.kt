package dev.modi.coverwallpaper

import android.graphics.Bitmap
import java.util.concurrent.CopyOnWriteArraySet

/** Shared "what's playing right now" state, written by the listener and read by the wallpaper. */
object NowPlaying {
    data class Track(
        val title: String? = null,
        val artist: String? = null,
        val playing: Boolean = false,
        /** Best still cover we have: full-res from Apple's catalogue, else the small one from the player. */
        val art: Bitmap? = null,
        /** Looping HLS video of the album's motion art, if it has one. */
        val motionUrl: String? = null,
        /** First frame of the motion art, shown while the video loads. */
        val motionPreview: Bitmap? = null,
    )

    @Volatile
    var current: Track = Track()
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun update(track: Track) {
        if (track == current) return
        current = track
        listeners.forEach { it() }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
}
