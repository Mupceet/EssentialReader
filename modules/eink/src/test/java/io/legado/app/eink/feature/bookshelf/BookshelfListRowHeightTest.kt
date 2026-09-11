package io.legado.app.eink.feature.bookshelf

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表行高 = max(基础封面高 120dp, worst-case 文字实需高)。
 *
 * [Density](1f, fontScale) 与宿主同语义：Android 的 sp→dp 走系统非线性
 * 字体缩放曲线（Compose 复刻查表插值，fontScale ≥ 1.03 生效）。排版阶梯
 * 重排后四行文字均为 24sp 行高（96sp 实需 + 行距 3×4dp + 内边距 2×2dp），
 * 默认倍率 112dp 仍由封面高主导；放大倍率起实需超出封面高，行高随实需
 * 自适应——非线性分支只断言自适应发生，不耦合系统曲线表常数；精确值用
 * 线性 [Density](1f) 分支锁定（行距/内边距计入）。
 */
class BookshelfListRowHeightTest {

    private fun rowHeight(fontScale: Float, showLatestChapter: Boolean = true) =
        bookshelfListRowHeight(
            density = Density(1f, fontScale),
            titleLineHeight = 24.sp,
            authorLineHeight = 24.sp,
            chapterLineHeight = 24.sp,
            showLatestChapter = showLatestChapter,
            rowSpacing = 4.dp,
            verticalPadding = 2.dp
        )

    @Test
    fun `默认倍率四行实需 112dp 由封面高主导`() {
        assertEquals(120.dp, rowHeight(1f))
    }

    @Test
    fun `默认倍率三行实需 84dp 由封面高主导`() {
        assertEquals(120.dp, rowHeight(1f, showLatestChapter = false))
    }

    @Test
    fun `放大倍率四行实需超出封面高行高随实需自适应`() {
        assertTrue(rowHeight(1.3f).value > 120f)
        assertTrue(rowHeight(1.6f).value > 120f)
    }

    @Test
    fun `1_6x 曲线三行实需仍在 120dp 内容框内`() {
        assertEquals(120.dp, rowHeight(1.6f, showLatestChapter = false))
    }

    @Test
    fun `实需高超过基础封面高时行高含行距与内边距`() {
        // Density(1f) 为线性区，纯函数验证 max() 取实需高：
        // 48 + 40×3 + 行距 4×3 + 内边距 2×2 = 184dp
        val height = bookshelfListRowHeight(
            density = Density(1f),
            titleLineHeight = 48.sp,
            authorLineHeight = 40.sp,
            chapterLineHeight = 40.sp,
            showLatestChapter = true,
            rowSpacing = 4.dp,
            verticalPadding = 2.dp
        )
        assertEquals(184.dp, height)
    }
}
