package io.legado.app.eink.feature.reader.screensaver

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderScreensaverViewportTest {

    @Test
    fun visibleStatusBar_leavesScaledBlankBandBeforeContent() {
        val viewport = resolveReaderScreensaverViewport(
            contentWidthPx = 1000,
            contentHeightPx = 1400,
            systemBarTopInsetPx = 50f,
            systemBarBottomInsetPx = 20f,
            targetWidthPx = 1860,
            targetHeightPx = 2480,
        )

        assertEquals(2480f / 1470f, viewport.scaleY, 0.0001f)
        assertEquals(50f * viewport.scaleY, viewport.contentTopPx, 0.0001f)
    }

    @Test
    fun hiddenStatusBar_startsPageAtCanvasTop() {
        val viewport = resolveReaderScreensaverViewport(
            contentWidthPx = 1000,
            contentHeightPx = 1400,
            systemBarTopInsetPx = 0f,
            systemBarBottomInsetPx = 20f,
            targetWidthPx = 1860,
            targetHeightPx = 2480,
        )

        assertEquals(0f, viewport.contentTopPx, 0f)
    }
}
