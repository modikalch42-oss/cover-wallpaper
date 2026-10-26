package dev.modi.coverwallpaper

import android.app.Activity
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.max

/** Setup screen: grant access, choose your normal wallpaper, then set the live wallpaper. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var normalPreview: ImageView
    private lateinit var normalLabel: TextView
    private val refresh: () -> Unit = { runOnUiThread { updateStatus() } }
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 3, pad, pad * 2)
        }

        root.addView(text(getString(R.string.app_name), 28f, Color.WHITE))
        status = text("", 16f, Color.LTGRAY).apply { setPadding(0, pad, 0, pad / 3) }
        root.addView(status)
        nowPlaying = text("", 15f, Color.rgb(250, 45, 85)).apply { setPadding(0, 0, 0, pad) }
        root.addView(nowPlaying)

        root.addView(button("1. Allow access to what's playing") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("If that's greyed out: open App info") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
            )
        })

        root.addView(text("Your normal wallpaper (shown when nothing's playing)", 15f, Color.WHITE)
            .apply { setPadding(0, pad, 0, pad / 3) })
        normalPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(pad * 6, (pad * 6 * 2.2f).toInt()).apply {
                bottomMargin = pad / 3
            }
            clipToOutline = true
            setBackgroundColor(Color.rgb(40, 40, 52))
        }
        root.addView(normalPreview)
        normalLabel = text("", 13f, Color.GRAY)
        root.addView(normalLabel)
        root.addView(button("2. Choose your normal wallpaper") { pickImage() })

        root.addView(Switch(this).apply {
            text = "Motion art on Wi-Fi only (saves data)"
            setTextColor(Color.LTGRAY)
            isChecked = Prefs.motionWifiOnly(this@MainActivity)
            setPadding(0, pad, 0, pad / 2)
            setOnCheckedChangeListener { _, on -> Prefs.setMotionWifiOnly(this@MainActivity, on) }
        })

        root.addView(button("3. Set as wallpaper") { setWallpaper() })

        root.addView(text(
            "Choose \"Home and lock screen\" when setting it.\n\n" +
                "If the access switch is greyed out, tap App info, then the ⋮ menu (top right) → " +
                "\"Allow restricted settings\", then try step 1 again.\n\n" +
                "Motion art is read from Apple Music's website. Not every album has it, and if Apple " +
                "changes their site it may stop — you'll still get the still cover.",
            13f, Color.GRAY
        ).apply { setPadding(0, pad, 0, 0) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 18, 24))
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        showNormalPreview()
    }

    private fun text(s: String, size: Float, colour: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(colour)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        NowPlaying.addListener(refresh)
        if (hasAccess()) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, MediaListenerService::class.java))
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
            t.title != null -> buildString {
                append("Now playing: ${t.title}")
                t.artist?.let { append(" — $it") }
                append(
                    when {
                        t.motionUrl != null -> "\n✓ Motion art"
                        t.art != null -> "\nStill cover (no motion art for this album)"
                        else -> "\nLooking up the cover…"
                    }
                )
            }
            hasAccess() -> "Play something in Apple Music to test it."
            else -> ""
        }
    }

    // ---------- normal wallpaper ----------

    private fun pickImage() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't open the photo picker", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || uri == null) return
        normalLabel.text = "Saving…"
        Thread {
            val ok = saveNormalWallpaper(uri)
            runOnUiThread {
                if (ok) {
                    Prefs.notifyChanged()
                    showNormalPreview()
                } else {
                    normalLabel.text = "Couldn't read that image — try another"
                }
            }
        }.start()
    }

    /** Copies the picked image into the app, scaled to the screen and the right way up. */
    private fun saveNormalWallpaper(uri: Uri): Boolean = try {
        val dm = resources.displayMetrics
        val longest = max(dm.widthPixels, dm.heightPixels) * 1.2f

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2

        var bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("decode failed")

        val rotation = contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotation != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        }

        val target = Prefs.normalWallpaperFile(this)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        tmp.renameTo(target)
    } catch (_: Exception) {
        false
    }

    private fun showNormalPreview() {
        val f = Prefs.normalWallpaperFile(this)
        if (f.exists()) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            normalPreview.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath, opts))
            normalLabel.text = "✓ Set — tap below to change it"
        } else {
            normalPreview.setImageDrawable(null)
            normalLabel.text = "Not set yet — you'll get a plain dark gradient"
        }
    }

    // ---------- wallpaper ----------

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

    companion object {
        private const val REQ_PICK = 42
    }
}
