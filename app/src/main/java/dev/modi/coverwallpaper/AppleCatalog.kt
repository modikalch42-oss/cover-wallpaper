package dev.modi.coverwallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks up the playing album in Apple's catalogue:
 *  1. iTunes Search API → full-resolution cover + the album's music.apple.com page
 *  2. that page → the "motionDetailTall" looping video (if the album has motion art)
 *
 * Step 2 reads Apple's public web page, which isn't an official API, so it can stop
 * working if Apple changes the site. Everything falls back to the still cover.
 * Background threads only.
 */
object AppleCatalog {

    data class Info(
        val art: Bitmap? = null,
        val motionUrl: String? = null,
        val motionPreview: Bitmap? = null,
    )

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0 Mobile Safari/537.36"

    private const val COUNTRY = "GB"
    private const val ART_SIZE = 1200

    private data class Hit(val artUrl: String, val pageUrl: String?)
    private data class Motion(val video: String, val previewTemplate: String?, val tall: Boolean)

    /** Calls [onUpdate] with the cover first, then again with motion art if there is any. */
    fun lookup(artist: String?, album: String?, title: String?, onUpdate: (Info) -> Unit) {
        val cleanAlbum = album
            ?.replace(Regex("\\s*-\\s*(Single|EP)\\s*$", RegexOption.IGNORE_CASE), "")
            ?.trim()
        val hit = find(artist, cleanAlbum, "album", "collectionName")
            ?: find(artist, title, "song", "trackName")
            ?: return

        val art = download(resize(hit.artUrl, ART_SIZE, ART_SIZE))
        if (art != null) onUpdate(Info(art))

        val page = hit.pageUrl?.let { get(it, html = true) }?.let { String(it) } ?: return
        val motion = parseMotion(page) ?: return
        val preview = motion.previewTemplate?.let { t ->
            val (pw, ph) = if (motion.tall) 1200 to 1600 else 1200 to 1200
            download(t.replace("{w}", "$pw").replace("{h}", "$ph").replace("{f}", "jpg"))
        }
        onUpdate(Info(art, motion.video, preview))
    }

    private fun resize(url: String, w: Int, h: Int) =
        url.replace(Regex("\\d+x\\d+bb"), "${w}x${h}bb")

    private fun find(artist: String?, name: String?, entity: String, nameField: String): Hit? {
        if (name.isNullOrBlank()) return null
        val term = URLEncoder.encode(listOfNotNull(artist, name).joinToString(" "), "UTF-8")
        val body = get("https://itunes.apple.com/search?term=$term&entity=$entity&limit=15&country=$COUNTRY")
            ?: return null
        val results = try { JSONObject(String(body)).optJSONArray("results") } catch (_: Exception) { null }
            ?: return null

        var fallback: Hit? = null
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val art = r.optString("artworkUrl100").takeIf { it.isNotBlank() } ?: continue
            val n = r.optString(nameField)
            val a = r.optString("artistName")
            val artistOk = artist.isNullOrBlank() || a.contains(artist, true) || artist.contains(a, true)
            if (!artistOk) continue     // never show a cover from a different artist
            val hit = Hit(art, r.optString("collectionViewUrl").takeIf { it.isNotBlank() })
            if (n.equals(name, true)) return hit
            if (fallback == null && (n.contains(name, true) || name.contains(n, true))) fallback = hit
        }
        return fallback
    }

    /** Finds the looping video in the album page's embedded data. Prefers the tall (phone) version. */
    private fun parseMotion(rawHtml: String): Motion? {
        val html = rawHtml.replace("\\/", "/").replace("\\u002F", "/")
        val videoRe = Regex("\"video\"\\s*:\\s*\"(https://[^\"]+?\\.m3u8)\"")
        val previewRe = Regex("\"url\"\\s*:\\s*\"(https://[^\"]+?\\{w\\}x\\{h\\}bb\\.\\{f\\})\"")

        for ((key, tall) in listOf("motionDetailTall" to true, "motionDetailSquare" to false)) {
            val idx = html.indexOf("\"$key\"")
            if (idx < 0) continue
            val window = html.substring(idx, minOf(html.length, idx + 5000))
            val video = videoRe.find(window)?.groupValues?.get(1) ?: continue
            val preview = previewRe.find(window)?.groupValues?.get(1)
            return Motion(video, preview, tall)
        }
        // Older page layout: the video player tag itself
        Regex("<amp-ambient-video[^>]+src=\"(https://[^\"]+?\\.m3u8)\"").find(html)?.let {
            return Motion(it.groupValues[1], null, false)
        }
        return null
    }

    private fun download(url: String): Bitmap? {
        val bytes = get(url) ?: return null
        return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Throwable) { null }
    }

    private fun get(url: String, html: Boolean = false): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                setRequestProperty("User-Agent", USER_AGENT)
                if (html) setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
            }
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
