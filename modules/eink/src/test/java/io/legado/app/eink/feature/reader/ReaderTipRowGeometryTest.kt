package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 页眉/页脚条带几何——逐式镜像宿主
 * `io.legado.app.feature.reader.core.model.ReaderTipRowLayout`：
 * 锚定「文字顶贴上边距 / 文字底贴下边距 / extent = 边距+行高+分割线」，
 * live 阅读页与屏保共用，本测试钉住公式防两份实现漂移。
 */
class ReaderTipRowGeometryTest {

    @Test
    fun extentSumsPaddingsLineHeightAndDivider() {
        // ascent 为负：lineHeight = descent − ascent = 10 − (−8) = 18
        assertEquals(
            4f + 18f + 6f + 1f,
            ReaderTipRowGeometry.extent(
                paddingTopPx = 4f,
                fontTopPx = -8f,
                fontBottomPx = 10f,
                paddingBottomPx = 6f,
                dividerExtentPx = 1f,
            ),
            0.0001f,
        )
        assertEquals(
            4f + 18f + 6f,
            ReaderTipRowGeometry.extent(4f, -8f, 10f, 6f),
            0.0001f,
        )
    }

    @Test
    fun headerBaselineAnchorsTextTopToPaddingTop() {
        // 基线 = paddingTop − ascent = 4 − (−8) = 12 → 字形顶恰在 paddingTop
        assertEquals(12f, ReaderTipRowGeometry.headerBaseline(4f, -8f), 0.0001f)
    }

    @Test
    fun footerBaselineAnchorsTextBottomToPaddingBottom() {
        // 基线 = 带高 − paddingBottom − descent = 30 − 6 − 10 = 14
        assertEquals(
            14f,
            ReaderTipRowGeometry.footerBaseline(30f, 6f, 10f),
            0.0001f,
        )
    }
}
