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
 */
internal data class ReaderScreensaverSettings(
    val screensaverEnabled: Boolean,
    val bookScreenSaverStyle: Int,
    val illustrationEnabled: Boolean,
    val illustrationValue: String?,
) {
    /** Selected protocol mode, or null when book wallpapers are disabled. */
    val mode: ReaderScreensaverMode?
        get() = if (screensaverEnabled) {
            ReaderScreensaverMode.fromStyle(bookScreenSaverStyle)
        } else {
            null
        }

    val active: Boolean
        get() = mode != null

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
 * that directory.
 */
internal enum class ReaderScreensaverMode(
    val style: Int,
    val wallpaperType: String,
    val targetDirectory: String,
) {
    COVER(
        style = ReaderScreensaverSettings.BOOK_COVER_STYLE,
        wallpaperType = "BOOK",
        targetDirectory = "/data/zhangyue/logo/book",
    ),
    READING_PROGRESS(
        style = ReaderScreensaverSettings.BOOK_READING_STYLE,
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
): Boolean = isIReaderBrand(input.manufacturer, input.brand) &&
    input.systemUiInstalled &&
    input.settings.active

/** Brand filter shared conceptually with `PageTurnEffectEngineImpl`. */
internal fun isIReaderBrand(manufacturer: String, brand: String): Boolean =
    manufacturer.equals(IREADER_BRAND, ignoreCase = true) ||
        brand.equals(IREADER_BRAND, ignoreCase = true)

internal const val IREADER_BRAND = "iReader"
internal const val IREADER_SYSTEM_UI_PACKAGE = "com.szzy.ireader.systemui"
