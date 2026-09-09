package io.legado.app.eink.feature.bookshelf

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 格宽公式：可用宽扣除左右内容边距（16dp × 2）与列间距（16dp × 列数-1）
 * 后均分。360dp 屏 3 列 = (360 - 32 - 32) / 3 ≈ 98.67dp。
 */
class BookshelfGridCellWidthTest {

    @Test
    fun `360dp 三列格宽约 98_67dp`() {
        assertEquals(98.67f, bookshelfGridCellWidth(360.dp, 3).value, 0.01f)
    }

    @Test
    fun `列数小于 1 钳制为单列`() {
        assertEquals(328f, bookshelfGridCellWidth(360.dp, 0).value, 0.01f)
        assertEquals(328f, bookshelfGridCellWidth(360.dp, -1).value, 0.01f)
    }

    @Test
    fun `列数越多格宽越窄`() {
        val widths = (2..6).map { bookshelfGridCellWidth(600.dp, it).value }
        assertEquals(widths, widths.sortedDescending())
        assertTrue(widths.zipWithNext().all { (a, b) -> a > b })
    }
}
