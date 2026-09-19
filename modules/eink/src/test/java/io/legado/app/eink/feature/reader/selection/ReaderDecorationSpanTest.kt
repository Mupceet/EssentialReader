package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderDecorationRun
import io.legado.app.eink.contract.ReaderPageLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 假等宽测量：每字符 10px，便于手算（与 ReaderTextSelectionTest 同款）。 */
private val measure: (String) -> Float = { it.length * 10f }

class ReaderDecorationSpanTest {

    private fun line(vararg chunks: String, positions: IntArray) = ReaderPageLine(
        baseY = 50f, isTitle = false, chunks = chunks.toList(),
        x = FloatArray(chunks.size) { i -> i * 100f },
        chapterPositions = positions, top = 30f, bottom = 70f,
    )

    @Test
    fun `装饰区间换算为 x 跨度`() {
        val l = line("abcdef", "gh", positions = intArrayOf(0, 6))
        // 字符 2..7（[start,end) 半开区间，跨两段）：右缘 = x[1] + 1 字符
        val span = decorationSpanX(
            l,
            ReaderDecorationRun(2, 7, underlineMode = 1, highlight = false, markingId = "m1"),
            measure,
        )
        assertEquals(20f, span!!.first)       // x[0] + 2 字符
        assertEquals(100f + 10f, span.second) // x[1] + 1 字符
    }

    @Test
    fun `区间越界返回 null`() {
        val l = line("abc", positions = intArrayOf(0))
        assertNull(decorationSpanX(l, ReaderDecorationRun(2, 9, 1, false, "m1"), measure))
    }

    @Test
    fun `段首缩进空白不落墨迹`() {
        // 行以段首缩进（两个全角空格）开头：run 覆盖整行时也从句首可见字符起笔
        val l = line("　　正文", positions = intArrayOf(0))
        val span = decorationSpanX(l, ReaderDecorationRun(0, 4, 1, false, "m1"), measure)
        assertEquals(20f, span!!.first)
        assertEquals(40f, span.second)
    }

    @Test
    fun `标记只覆盖段首缩进时不画装饰`() {
        val l = line("　　正文", positions = intArrayOf(0))
        assertNull(decorationSpanX(l, ReaderDecorationRun(0, 2, 1, false, "m1"), measure))
    }
}
