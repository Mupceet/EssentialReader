package io.legado.app.eink.feature.reader.screensaver

import android.content.Context
import android.content.Intent

/** Sends the explicit iReader broadcast after a new `.rmb` is durable. */
internal object ReaderScreensaverNotifier {

    private const val ACTION_LOCK_SCREEN_WALLPAPER_CHANGED =
        "com.szzy.ireader.ink.wallpaper.ACTION_LOCK_SCREEN_WALLPAPER_CHANGED"

    fun notifyChanged(context: Context, mode: ReaderScreensaverMode) {
        val intent = Intent(ACTION_LOCK_SCREEN_WALLPAPER_CHANGED).apply {
            setPackage(IREADER_SYSTEM_UI_PACKAGE)
            putExtra("extra_wallpaper_type", mode.wallpaperType)
            putExtra("extra_order_type", "CAROUSEL")
            if (mode == ReaderScreensaverMode.READING_PROGRESS) {
                putExtra("extra_orientation_type", 0)
                putExtra("extra_refresh_interval_type", 0L)
            }
        }
        context.sendBroadcast(intent)
    }
}
