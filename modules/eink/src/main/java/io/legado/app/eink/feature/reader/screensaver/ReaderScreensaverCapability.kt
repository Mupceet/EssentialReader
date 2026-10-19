package io.legado.app.eink.feature.reader.screensaver

/**
 * Settings that control the iReader book lock-screen implementation.
 *
 * The illustration fields are independent from the page screenshot. A failed
 * or disabled illustration degrades to a page-only image and must not block
 * publication.
 */
internal data class ReaderScreensaverSettings(
    val screensaverEnabled: Boolean,
    val bookScreenSaverStyle: Int,
    val illustrationEnabled: Boolean,
    val illustrationValue: String?,
) {
    /** iReader book-reading style 5 is the third-party `.rmb` wallpaper mode. */
    val active: Boolean
        get() = screensaverEnabled && bookScreenSaverStyle == BOOK_READING_STYLE

    companion object {
        const val BOOK_READING_STYLE = 5
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
