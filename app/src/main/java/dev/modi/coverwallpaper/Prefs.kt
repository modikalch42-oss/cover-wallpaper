package dev.modi.coverwallpaper

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/** App settings, shared between the setup screen and the wallpaper. */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_WIFI_ONLY = "motion_wifi_only"

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun normalWallpaperFile(ctx: Context) = File(ctx.filesDir, "normal_wallpaper.jpg")

    fun motionWifiOnly(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_WIFI_ONLY, false)

    fun setMotionWifiOnly(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_WIFI_ONLY, value).apply()
        notifyChanged()
    }

    /** Motion videos use data, so they can be limited to Wi-Fi (unmetered networks). */
    fun motionAllowedNow(ctx: Context): Boolean {
        if (!motionWifiOnly(ctx)) return true
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        return !cm.isActiveNetworkMetered
    }

    fun notifyChanged() = listeners.forEach { it() }
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
}
