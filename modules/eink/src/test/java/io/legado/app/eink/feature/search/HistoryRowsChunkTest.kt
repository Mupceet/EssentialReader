package io.legado.app.eink.feature.search

import io.legado.app.eink.contract.SearchHistoryUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRowsChunkTest {

    @Test
    fun `empty history yields no rows`() {
        val rows = chunk(widths = emptyList(), rowWidth = 100, spacing = 5)

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `chips fitting within row width stay on one row`() {
        val rows = chunk(widths = listOf(30, 30, 30), rowWidth = 100, spacing = 5)

        assertEquals(listOf(3), rows.map { it.size })
        assertEquals(listOf("w-30", "w-30", "w-30"), rows.single().map { it.word })
    }

    @Test
    fun `overflow starts a new row preserving first-seen order`() {
        // 40 + 5 + 40 = 85 fits; +5 +40 = 130 overflows 100
        val rows = chunk(widths = listOf(40, 40, 40), rowWidth = 100, spacing = 5)

        assertEquals(listOf(2, 1), rows.map { it.size })
        assertEquals(listOf("w-40", "w-40"), rows[0].map { it.word })
        assertEquals(listOf("w-40"), rows[1].map { it.word })
    }

    @Test
    fun `spacing is counted between chips`() {
        // 50 + spacing + 45 vs rowWidth 100：spacing 6 溢出拆行，spacing 4 同行
        assertEquals(
            listOf(1, 1),
            chunk(listOf(50, 45), rowWidth = 100, spacing = 6).map { it.size },
        )
        assertEquals(
            listOf(2),
            chunk(listOf(50, 45), rowWidth = 100, spacing = 4).map { it.size },
        )
    }

    @Test
    fun `oversized chip is capped to row width and occupies its own row`() {
        // 宽 120 的词封顶到行宽 100：与前后词都拼不进同一行，各自成行
        val rows = chunk(widths = listOf(30, 120, 30), rowWidth = 100, spacing = 5)

        assertEquals(listOf(1, 1, 1), rows.map { it.size })
        assertEquals(listOf("w-30"), rows[0].map { it.word })
        assertEquals(listOf("w-120"), rows[1].map { it.word })
        assertEquals(listOf("w-30"), rows[2].map { it.word })
    }

    @Test
    fun `every row respects the row width budget`() {
        val widths = listOf(50, 30, 60, 45, 20, 20, 20, 70, 10)
        val rows = chunk(widths = widths, rowWidth = 100, spacing = 5)

        rows.forEach { row ->
            val rowWidth = row.sumOf { widthOf(it) } + 5 * (row.size - 1)
            assertTrue("row $row exceeds budget: $rowWidth", rowWidth <= 100)
        }
        assertEquals(widths.size, rows.sumOf { it.size })
    }

    private fun chunk(widths: List<Int>, rowWidth: Int, spacing: Int): List<List<SearchHistoryUiModel>> {
        val history = widths.map { SearchHistoryUiModel(word = "w-$it") }
        return chunkHistoryRows(
            history = history,
            rowWidth = rowWidth,
            spacing = spacing,
            labelWidth = { widthOf(it) },
        )
    }

    private fun widthOf(keyword: SearchHistoryUiModel): Int =
        keyword.word.removePrefix("w-").toInt()
}
