package io.legado.app.eink.contract

import android.content.Context
import android.content.SharedPreferences

/**
 * E-Ink 自有界面偏好（随模块走，不占用宿主 AppConfig）。
 *
 * 键名与历史版本（散落在宿主 AppConfig/ReaderViewModel 的时期）逐字一致，
 * 且落在同一默认 SharedPreferences 文件（`<packageName>_preferences`），
 * 老用户设置无损迁移。
 */
object EInkSettings {

    private const val KEY_BOOKSHELF_GRID = "einkBookshelfGrid"
    private const val KEY_READER_KEEP_SCREEN_ON = "einkReaderKeepScreenOn"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 宿主 E-Ink 入口 onCreate 调用（幂等）。 */
    fun attach(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.getSharedPreferences(
                        context.packageName + "_preferences", Context.MODE_PRIVATE
                    )
                }
            }
        }
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: error("EInkSettings 未初始化：宿主入口需先调用 attach(context)")

    /** 书架网格布局（true = 网格）。 */
    var isBookshelfGrid: Boolean
        get() = requirePrefs().getBoolean(KEY_BOOKSHELF_GRID, false)
        set(value) = requirePrefs().edit().putBoolean(KEY_BOOKSHELF_GRID, value).apply()

    /** 阅读页保持屏幕常亮。 */
    var readerKeepScreenOn: Boolean
        get() = requirePrefs().getBoolean(KEY_READER_KEEP_SCREEN_ON, false)
        set(value) = requirePrefs().edit().putBoolean(KEY_READER_KEEP_SCREEN_ON, value).apply()
}
