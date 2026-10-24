package io.legado.app.eink.feature.reader.screensaver

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Android/firmware adapter for iReader's book lock-screen settings and assets.
 *
 * This class only reads platform state. It never writes Settings.System and
 * never creates an overlay. All failures are represented as disabled/failed
 * image reads so the page image can still be published.
 */
internal class ReaderScreensaverSystem(context: Context) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    fun isTargetPlatform(): Boolean {
        if (!isIReaderBrand(Build.MANUFACTURER, Build.BRAND)) return false
        return systemUiInstalled()
    }

    fun readSettings(): ReaderScreensaverSettings {
        val screenSaverEnabled =
            readSecureInt(KEY_SCREENSAVER_ENABLED, defaultValue = 0) == 1
        val style = readInt(KEY_BOOK_SCREEN_SAVER_STYLE, defaultValue = -1)
        val illustrationEnabled =
            readInt(KEY_ENABLE_ILLUSTRATION, defaultValue = 0) == 1
        val illustrationValue = runCatching {
            Settings.System.getString(resolver, KEY_ILLUSTRATION_INFO)
        }.onFailure {
            Log.w(TAG, "read illustration setting failed: ${it.javaClass.simpleName}")
        }.getOrNull()

        return ReaderScreensaverSettings(
            screensaverEnabled = screenSaverEnabled,
            bookScreenSaverStyle = style,
            illustrationEnabled = illustrationEnabled,
            illustrationValue = illustrationValue,
            activeLockScreenTypeValue = readActiveLockScreenTypeValue(),
        )
    }

    /**
     * 活动锁屏类型：`wallpaper_lock_screen_info`（格式 "type,order"）的首段。
     * 固件按它决定息屏显示哪个目录的壁纸；未设置时固件回落
     * COMPAT_x/SYSTEM 兜底链（非图书类），这里同样返回 null。
     */
    fun readActiveLockScreenTypeValue(): Int? = runCatching {
        Settings.System.getString(resolver, KEY_ACTIVE_LOCK_SCREEN_INFO)
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.toIntOrNull()
    }.getOrNull()

    fun registerObserver(observer: ContentObserver) {
        runCatching {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY_SCREENSAVER_ENABLED),
                false,
                observer,
            )
        }.onFailure {
            Log.w(TAG, "register secure observer failed: ${it.javaClass.simpleName}")
        }
        OBSERVED_SETTINGS.forEach { key ->
            runCatching {
                resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer)
            }.onFailure {
                Log.w(TAG, "register observer failed for $key: ${it.javaClass.simpleName}")
            }
        }
    }

    fun unregisterObserver(observer: ContentObserver) {
        runCatching { resolver.unregisterContentObserver(observer) }
    }

    /**
     * Loads the system-selected illustration. Built-in assets use iReader's
     * FileProvider; custom values are normally absolute PNG paths.
     */
    fun loadIllustration(settings: ReaderScreensaverSettings): Bitmap? {
        if (!settings.illustrationEnabled) return null
        val value = settings.illustrationValue?.trim().orEmpty()
        if (value.isEmpty()) return null

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            when {
                value.startsWith("content://") -> {
                    resolver.openInputStream(Uri.parse(value))
                        ?.use { BitmapFactory.decodeStream(it, null, options) }
                }

                value.startsWith("file://") -> {
                    resolver.openInputStream(Uri.parse(value))
                        ?.use { BitmapFactory.decodeStream(it, null, options) }
                }

                value.startsWith("/") -> {
                    FileInputStream(File(value)).use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                }

                else -> {
                    val uri = Uri.parse("$BUILTIN_ILLUSTRATION_URI_PREFIX/${Uri.encode(value)}")
                    resolver.openInputStream(uri)
                        ?.use { BitmapFactory.decodeStream(it, null, options) }
                }
            }
        }.onFailure {
            Log.w(TAG, "illustration load failed: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun readInt(key: String, defaultValue: Int): Int =
        runCatching {
            Settings.System.getInt(resolver, key, defaultValue)
        }.onFailure {
            Log.w(TAG, "read setting failed for $key: ${it.javaClass.simpleName}")
        }.getOrDefault(defaultValue)

    private fun readSecureInt(key: String, defaultValue: Int): Int =
        runCatching {
            Settings.Secure.getInt(resolver, key, defaultValue)
        }.onFailure {
            Log.w(TAG, "read secure setting failed for $key: ${it.javaClass.simpleName}")
        }.getOrDefault(defaultValue)

    @Suppress("DEPRECATION")
    private fun systemUiInstalled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                IREADER_SYSTEM_UI_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            appContext.packageManager.getPackageInfo(IREADER_SYSTEM_UI_PACKAGE, 0)
        }
        true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "ReaderScreensaver"
        internal const val KEY_SCREENSAVER_ENABLED = "screensaver_enabled"
        internal const val KEY_BOOK_SCREEN_SAVER_STYLE = "book_screen_saver_style"
        internal const val KEY_ENABLE_ILLUSTRATION =
            "book_wallpaper_enable_illustration"
        internal const val KEY_ILLUSTRATION_INFO = "Book_Wallpaper_Illustration_info"
        internal const val KEY_ACTIVE_LOCK_SCREEN_INFO = "wallpaper_lock_screen_info"
        internal const val BUILTIN_ILLUSTRATION_URI_PREFIX =
            "content://com.szzy.ireader.ink.wallpaper.fileprovider/wallpaper"

        private val OBSERVED_SETTINGS = listOf(
            KEY_BOOK_SCREEN_SAVER_STYLE,
            KEY_ENABLE_ILLUSTRATION,
            KEY_ILLUSTRATION_INFO,
            // 活动类型切换（如实时屏保 ⇄ 图书）必须即时反映到发布门禁：
            // 切走即静默、切回即恢复发布；仍走 settingsObserver 的
            // FORCE_RECLAIM_INTERVAL_MS 限频，防固件回写观察键形成自激环
            KEY_ACTIVE_LOCK_SCREEN_INFO,
        )
    }
}
