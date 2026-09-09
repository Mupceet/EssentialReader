package io.legado.app.eink.feature.bookshelf

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表行高 = max(基础封面高 120dp, worst-case 文字实需高)。
 *
 * [Density](1f, fontScale) 与宿主同语义：Android 的 sp→dp 走系统非线性
 * 字体缩放曲线（Compose 复刻查表插值，fontScale ≥ 1.03 生效），非线性
 * 放大——钉死 90dp 时按真实曲线约 1.35x 起四行实需超出内容框（86dp）
 * 截断。支持区间内的用例用真实字体档位（标题 titleMedium 24sp、信息行
 * 16sp）锁定 120dp 底座全程不截断；max() 自适应分支用放大档位纯函数
 * 验证，不耦合系统曲线表常数。
 */
class BookshelfListRowHeightTest {

    private fun rowHeight(fontScale: Float, showLatestChapter: Boolean = true) =
        bookshelfListRowHeight(
            density = Density(1f, fontScale),
            titleLineHeight = 24.sp,
            authorLineHeight = 16.sp,
            chapterLineHeight = 16.sp,
            showLatestChapter = showLatestChapter
        )

    @Test
    fun `默认倍率四行实需 78dp 由封面高主导`() {
        assertEquals(120.dp, rowHeight(1f))
    }

    @Test
    fun `默认倍率三行实需 60dp 由封面高主导`() {
        assertEquals(120.dp, rowHeight(1f, showLatestChapter = false))
    }

    @Test
    fun `1_3x 曲线四行实需约 82_8dp 由封面高主导`() {
        assertEquals(120.dp, rowHeight(1.3f))
    }

    @Test
    fun `1_6x 曲线四行实需约 101_6dp 仍在 120dp 内容框内`() {
        assertEquals(120.dp, rowHeight(1.6f))
    }

    @Test
    fun `1_6x 曲线三行实需约 77_6dp 由封面高主导`() {
        assertEquals(120.dp, rowHeight(1.6f, showLatestChapter = false))
    }

    @Test
    fun `实需高超过基础封面高时行高随实需自适应`() {
        // Density(1f) 为线性区，纯函数验证 max() 取实需高：48 + 40×3 = 168dp
        val height = bookshelfListRowHeight(
            density = Density(1f),
            titleLineHeight = 48.sp,
            authorLineHeight = 40.sp,
            chapterLineHeight = 40.sp,
            showLatestChapter = true
        )
        assertEquals(168.dp, height)
    }
}
