package dev.modi.coverwallpaper

import android.app.Activity
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Simple setup screen: grant access, then set the wallpaper. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private val refresh: () -> Unit = { runOnUiThread { updateStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 3, pad, pad)
            setBackgroundColor(Color.rgb(18, 18, 24))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTextColor(Color.WHITE)
        })

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, pad, 0, pad / 2)
        }
        root.addView(status)

        nowPlaying = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(250, 45, 85))
            setPadding(0, 0, 0, pad)
        }
        root.addView(nowPlaying)

        root.addView(Button(this).apply {
            text = "1. Allow access to what's playing"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "If that's greyed out: open App info"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        })

        root.addView(Button(this).apply {
            text = "2. Set as wallpaper"
            setOnClickListener { setWallpaper() }
        })

        root.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, pad, 0, 0)
            text = "Tip: when setting it, choose \"Home and lock screen\" so it shows on your lock screen too.\n\n" +
                "If the access switch is greyed out, tap the App info button, then the ⋮ menu (top right) → " +
                "\"Allow restricted settings\", then try step 1 again."
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        NowPlaying.addListener(refresh)
        if (hasAccess()) {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(this, MediaListenerService::class.java)
                )
            } catch (_: Exception) { }
        }
        updateStatus()
    }

    override fun onPause() {
        NowPlaying.removeListener(refresh)
        super.onPause()
    }

    private fun hasAccess(): Boolean =
        getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, MediaListenerService::class.java))

    private fun updateStatus() {
        status.text = if (hasAccess()) "✓ Access granted" else "✗ Needs access to see what's playing"
        val t = NowPlaying.current
        nowPlaying.text = when {
            t.title != null -> "Now playing: ${t.title}${t.artist?.let { " — $it" } ?: ""}" +
                if (t.art == null) " (no artwork)" else ""
            hasAccess() -> "Play something in Apple Music to test it."
            else -> ""
        }
    }

    private fun setWallpaper() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, AlbumArtWallpaper::class.java)
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't open the wallpaper picker", Toast.LENGTH_LONG).show()
            }
        }
    }
}
