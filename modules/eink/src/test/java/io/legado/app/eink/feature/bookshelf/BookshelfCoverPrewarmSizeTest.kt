package io.legado.app.eink.feature.bookshelf

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.BookshelfStyle
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTypography
import io.legado.app.eink.feature.common.EInkCoverHeight
import io.legado.app.eink.feature.common.EInkCoverWidth
import io.legado.app.eink.feature.common.coverTargetSizePx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 预热尺寸与显示路径的 parity：[prewarmCoverTargetSize] 必须与 HomeRoute
 * 显示分支产出同一像素值（封面内存缓存键含尺寸，见 buildEInkCoverRequest
 * KDoc）——失配即预热项不被同步命中、纯浪费。本测试按 HomeRoute 的组合
 * 链独立重算参考值钉住两侧，任一侧公式漂移（如网格边距、行高参数变化）
 * 在此红灯。
 */
class BookshelfCoverPrewarmSizeTest {

    private val density = Density(density = 2f, fontScale = 1f)

    @Test
    fun `网格模式与显示链同像素`() {
        val style = BookshelfStyle(isGridLayout = true, gridCoverWidth = 120)
        val screenWidthDp = 360

        // HomeRoute 网格分支参考链（BoxWithConstraints maxWidth 同值代入）
        val columns = adaptiveGridColumns(screenWidthDp.dp, style.gridCoverWidth.dp)
        val cellWidth = bookshelfGridCellWidth(screenWidthDp.dp, columns)
        val expected = coverTargetSizePx(
            cellWidth,
            cellWidth * (EInkCoverHeight / EInkCoverWidth),
            density,
        )

        assertEquals(expected, prewarmCoverTargetSize(style, density, screenWidthDp))
    }

    @Test
    fun `列表模式与显示链同像素`() {
        val style = BookshelfStyle(isGridLayout = false, showLatestChapter = true)
        val screenWidthDp = 360

        // HomeRoute 列表分支参考链（同 typography/spacing 参数）
        val rowHeight = bookshelfListRowHeight(
            density = density,
            titleLineHeight = EInkTypography.titleMedium.lineHeight,
            authorLineHeight = EInkTypography.bodyMedium.lineHeight,
            chapterLineHeight = EInkTypography.bodyMedium.lineHeight,
            showLatestChapter = style.showLatestChapter,
            rowSpacing = EInkSpacing.xs,
            verticalPadding = EInkSpacing.xxs,
        )
        val expected = coverTargetSizePx(
            rowHeight * (EInkCoverWidth / EInkCoverHeight),
            rowHeight,
            density,
        )

        assertEquals(expected, prewarmCoverTargetSize(style, density, screenWidthDp))
    }

    @Test
    fun `字体缩放下行高随之伸缩保持 parity`() {
        // sp→dp 走非线性曲线（bookshelfListRowHeight KDoc），放大倍率下
        // 预热必须用真实 Density 才能追上显示路径
        val scaled = Density(density = 2f, fontScale = 1.3f)
        val style = BookshelfStyle(isGridLayout = false, showLatestChapter = false)

        val rowHeight = bookshelfListRowHeight(
            density = scaled,
            titleLineHeight = EInkTypography.titleMedium.lineHeight,
            authorLineHeight = EInkTypography.bodyMedium.lineHeight,
            chapterLineHeight = EInkTypography.bodyMedium.lineHeight,
            showLatestChapter = style.showLatestChapter,
            rowSpacing = EInkSpacing.xs,
            verticalPadding = EInkSpacing.xxs,
        )
        val expected = coverTargetSizePx(
            rowHeight * (EInkCoverWidth / EInkCoverHeight),
            rowHeight,
            scaled,
        )

        assertEquals(expected, prewarmCoverTargetSize(style, scaled, 360))
    }

    @Test
    fun `非法屏宽放弃预热`() {
        assertNull(prewarmCoverTargetSize(BookshelfStyle(), density, 0))
        assertNull(prewarmCoverTargetSize(BookshelfStyle(), density, -1))
    }
}
