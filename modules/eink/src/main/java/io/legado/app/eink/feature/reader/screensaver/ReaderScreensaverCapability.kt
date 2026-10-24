package io.legado.app.eink.feature.reader.screensaver

/**
 * Settings that control the iReader book lock-screen implementation.
 *
 * iReader exposes two book wallpaper styles through the same setting:
 * style 2 composites the current book cover with the selected illustration;
 * style 5 publishes the current page as reading progress.
 *
 * The illustration fields are independent from the base image. A failed or
 * disabled illustration degrades to the page/cover-only image and must not
 * block publication.
 *
 * [activeLockScreenTypeValue] is the firmware's *currently active* lock-screen
 * type (first segment of `wallpaper_lock_screen_info`, see
 * [ReaderScreensaverSystem.readActiveLockScreenTypeValue]). Publishing must be
 * gated by it: SystemUI switches its display model to whatever type our
 * refresh broadcast names, without validating the user's current selection —
 * publishing while another type (e.g. the dynamic screensaver) is active would
 * hijack the display. The firmware resolves an unset value to its
 * COMPAT_x/SYSTEM fallback chain, never to a book type, so null also blocks.
 */
internal data class ReaderScreensaverSettings(
    val screensaverEnabled: Boolean,
    val bookScreenSaverStyle: Int,
    val illustrationEnabled: Boolean,
    val illustrationValue: String?,
    val activeLockScreenTypeValue: Int?,
) {
    /** Selected protocol mode, or null when book wallpapers are disabled. */
    val mode: ReaderScreensaverMode?
        get() = if (screensaverEnabled) {
            ReaderScreensaverMode.fromStyle(bookScreenSaverStyle)
        } else {
            null
        }

    companion object {
        const val BOOK_COVER_STYLE = 2
        const val BOOK_READING_STYLE = 5
    }
}

/**
 * The two iReader book wallpaper protocols supported by this adapter.
 *
 * The type and directory values are part of the firmware contract: SystemUI
 * resolves the broadcast type to its directory, then scans `.rmb` files in
 * that directory. [typeValue] is the persistent
 * `WallpaperLockScreenType` value stored in `wallpaper_lock_screen_info`.
 */
internal enum class ReaderScreensaverMode(
    val style: Int,
    val typeValue: Int,
    val wallpaperType: String,
    val targetDirectory: String,
) {
    COVER(
        style = ReaderScreensaverSettings.BOOK_COVER_STYLE,
        typeValue = IREADER_LOCK_SCREEN_TYPE_BOOK,
        wallpaperType = "BOOK",
        targetDirectory = "/data/zhangyue/logo/book",
    ),
    READING_PROGRESS(
        style = ReaderScreensaverSettings.BOOK_READING_STYLE,
        typeValue = IREADER_LOCK_SCREEN_TYPE_READING_PROGRESS,
        wallpaperType = "BOOK_READING_PROGRESS",
        targetDirectory = "/data/zhangyue/logo/reading_progress",
    ),
    ;

    companion object {
        fun fromStyle(style: Int): ReaderScreensaverMode? =
            entries.firstOrNull { it.style == style }
    }
}

/**
 * Device/firmware gate for the iReader lock-screen protocol.
 *
 * Brand filtering intentionally mirrors the existing PAGE_H water-ripple
 * adapter: iReader is identified from manufacturer or brand, then the hidden
 * firmware surface is verified independently. Here the package check replaces
 * the EPDC reflection probe because the book wallpaper protocol belongs to
 * `com.szzy.ireader.systemui`.
 */
internal data class ReaderScreensaverCapabilityInput(
    val manufacturer: String,
    val brand: String,
    val systemUiInstalled: Boolean,
    val settings: ReaderScreensaverSettings,
)

internal fun resolveReaderScreensaverCapability(
    input: ReaderScreensaverCapabilityInput,
): Boolean {
    val mode = input.settings.mode ?: return false
    return isIReaderBrand(input.manufacturer, input.brand) &&
        input.systemUiInstalled &&
        // 活动锁屏类型必须与待发布模式一致才允许发布+广播：SystemUI 收到
        // 刷新广播会无条件切到广播声明的显示模型（不校验用户当前选择），
        // 类型不匹配时任何写入/广播都会破坏用户所选屏保（如实时屏保）
        input.settings.activeLockScreenTypeValue == mode.typeValue
}

/** Brand filter shared conceptually with `PageTurnEffectEngineImpl`. */
internal fun isIReaderBrand(manufacturer: String, brand: String): Boolean =
    manufacturer.equals(IREADER_BRAND, ignoreCase = true) ||
        brand.equals(IREADER_BRAND, ignoreCase = true)

internal const val IREADER_BRAND = "iReader"
internal const val IREADER_SYSTEM_UI_PACKAGE = "com.szzy.ireader.systemui"

/**
 * 反编译自固件 `WallpaperLockScreenType` 的持久化类型值（三方一致：设置
 * 应用 / SystemUI / 官方阅读应用）。DYNAMIC(实时屏保) 仅用于门控比对，
 * 图书类屏保之外的类型不得硬编码用于发布。
 */
internal const val IREADER_LOCK_SCREEN_TYPE_BOOK = 2
internal const val IREADER_LOCK_SCREEN_TYPE_READING_PROGRESS = 16
internal const val IREADER_LOCK_SCREEN_TYPE_DYNAMIC = 25

/**
 * 固件约定：`/data/zhangyue/logo` 下各屏保目录与 `.rmb` 文件均为 0777
 * （设置应用与官方阅读应用每次写入后都会 chmod 777 目录+文件）。目录由
 * 谁创建不重要，但权限必须放开——0700 目录会让设置预览写入 EACCES
 * 闪退、SystemUI 列不出目录回落内置默认图。
 */
internal const val IREADER_LOGO_MODE_0777 = 0x1FF
