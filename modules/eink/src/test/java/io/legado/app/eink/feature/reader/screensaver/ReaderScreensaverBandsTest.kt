package io.legado.app.eink.feature.reader.screensaver

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 窗口坐标分带（画布全屏、避让并入 contentPadding 后的屏保几何）：
 * 正文带 = [顶内容边距 + 页眉预留, 底内容边距之上 − 页脚预留(可见时)]。
 */
class ReaderScreensaverBandsTest {

    @Test
    fun bandsMirrorWindowSpaceContentRegion() {
        val bands = resolveReaderScreensaverBands(
            sourceHeightPx = 2480,
            systemBarTopInsetPx = 60f,
            systemBarBottomInsetPx = 40f,
            headerExtentPx = 70f,
            footerExtentPx = 90f,
            footerVisible = true,
        )
        assertEquals(60f + 70f, bands.bodyTopPx, 0.0001f)
        assertEquals(2480f - 40f - 90f, bands.bodyBottomPx, 0.0001f)
    }

    @Test
    fun hiddenHeaderAndFooterCollapseTheirBands() {
        // extent 随可见性塌缩：页眉隐 → headerExtent 传 0；页脚隐 → 不预留
        val bands = resolveReaderScreensaverBands(
            sourceHeightPx = 2480,
            systemBarTopInsetPx = 60f,
            systemBarBottomInsetPx = 40f,
            headerExtentPx = 0f,
            footerExtentPx = 0f,
            footerVisible = false,
        )
        assertEquals(60f, bands.bodyTopPx, 0.0001f)
        assertEquals(2480f - 40f, bands.bodyBottomPx, 0.0001f)
    }

    @Test
    fun hiddenStatusBarMovesBodyBandToWindowTop() {
        val bands = resolveReaderScreensaverBands(
            sourceHeightPx = 2480,
            systemBarTopInsetPx = 0f,
            systemBarBottomInsetPx = 0f,
            headerExtentPx = 70f,
            footerExtentPx = 90f,
            footerVisible = true,
        )
        assertEquals(70f, bands.bodyTopPx, 0.0001f)
        assertEquals(2480f - 90f, bands.bodyBottomPx, 0.0001f)
    }
}
