package dev.modi.coverwallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Apple Music only hands Android a small cover image, so we look the album up in
 * Apple's public iTunes catalogue and download the full-resolution artwork instead.
 * Runs on a background thread only.
 */
object HiResArt {
    private const val SIZE = 2400          // px — enough to fill a Pixel screen with room to zoom
    private const val COUNTRY = "GB"

    fun fetch(artist: String?, album: String?, title: String?): Bitmap? {
        val cleanAlbum = album
            ?.replace(Regex("\\s*-\\s*(Single|EP)\\s*$", RegexOption.IGNORE_CASE), "")
            ?.trim()
        val small = find(artist, cleanAlbum, "album", "collectionName")
            ?: find(artist, title, "song", "trackName")
            ?: return null
        val big = small.replace(Regex("\\d+x\\d+bb"), "${SIZE}x${SIZE}bb")
        return download(big) ?: download(small.replace(Regex("\\d+x\\d+bb"), "1200x1200bb"))
    }

    private fun find(artist: String?, name: String?, entity: String, nameField: String): String? {
        if (name.isNullOrBlank()) return null
        val term = URLEncoder.encode(listOfNotNull(artist, name).joinToString(" "), "UTF-8")
        val body = get("https://itunes.apple.com/search?term=$term&entity=$entity&limit=15&country=$COUNTRY")
            ?: return null
        val results = try { JSONObject(String(body)).optJSONArray("results") } catch (_: Exception) { null }
            ?: return null

        var fallback: String? = null
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val art = r.optString("artworkUrl100").takeIf { it.isNotBlank() } ?: continue
            val n = r.optString(nameField)
            val a = r.optString("artistName")
            val artistOk = artist.isNullOrBlank() || a.contains(artist, true) || artist.contains(a, true)
            if (!artistOk) continue
            if (n.equals(name, true)) return art
            if (fallback == null && (n.contains(name, true) || name.contains(n, true))) fallback = art
        }
        return fallback   // never guess a cover from a different artist
    }

    private fun download(url: String): Bitmap? {
        val bytes = get(url) ?: return null
        return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Throwable) { null }
    }

    private fun get(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
            }
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
