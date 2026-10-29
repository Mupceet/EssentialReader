package io.legado.app.eink.feature.reader.screensaver

internal data class ReaderScreensaverViewport(
    val scaleX: Float,
    val scaleY: Float,
    val contentTopPx: Float,
)

/**
 * Maps the reader content viewport into the device-sized wallpaper canvas
 * while preserving the system-bar bands outside that viewport.
 *
 * When the status bar is visible the reader viewport starts below it, so the
 * generated image leaves the same scaled band blank above the page. Hidden
 * status bars have a zero top inset and let the page start at the canvas top.
 */
internal fun resolveReaderScreensaverViewport(
    contentWidthPx: Int,
    contentHeightPx: Int,
    systemBarTopInsetPx: Float,
    systemBarBottomInsetPx: Float,
    targetWidthPx: Int,
    targetHeightPx: Int,
): ReaderScreensaverViewport {
    val sourceWidth = contentWidthPx.coerceAtLeast(1)
    val sourceHeight = contentHeightPx.coerceAtLeast(1)
    val fullSourceHeight = sourceHeight +
        systemBarTopInsetPx.coerceAtLeast(0f) +
        systemBarBottomInsetPx.coerceAtLeast(0f)
    val scaleX = targetWidthPx.toFloat() / sourceWidth
    val scaleY = targetHeightPx.toFloat() / fullSourceHeight.coerceAtLeast(1f)
    return ReaderScreensaverViewport(
        scaleX = scaleX,
        scaleY = scaleY,
        contentTopPx = systemBarTopInsetPx.coerceAtLeast(0f) * scaleY,
    )
}
