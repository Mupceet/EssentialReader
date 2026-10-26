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

    @Test
    fun `逐段测量版按各段画笔测宽`() {
        // 行内段 1 带字号覆盖：其字符宽 20px（段 0 仍 10px）。装饰区间整体
        // 落在段 1 时，span 两条缘都必须用段 1 的尺——否则高亮带/下划线与
        // 覆盖段字形错位
        val l = line("abc", "de", positions = intArrayOf(0, 3))
        val measureChunk: (Int, String) -> Float = { chunk, text ->
            text.length * if (chunk == 0) 10f else 20f
        }
        // 区间 [4,5)：整体在段 1 内，左缘 = x[1] + 1 字符 × 20px，右缘 = +2 字符
        val span = decorationSpanX(
            l,
            ReaderDecorationRun(4, 5, underlineMode = 1, highlight = false, markingId = "m1"),
            measureChunk,
        )
        assertEquals(100f + 20f, span!!.first)
        assertEquals(100f + 40f, span.second)
    }

    @Test
    fun `段边界起笔不圈进段间空白`() {
        // 引擎把段间间隙（letterSpacing、命中字距）计入前项 advance、不进
        // 字形 bounds——完整模式装饰从元素 bounds.left 起笔。模块快照的
        // 段间真值只有「下一段的 x」：起笔落段边界时必须取 x[1]（100f），
        // 按前段前缀宽测量会差出整段间隙（30f），命中字距大时空白被圈进
        // 首字（真机反馈）
        val l = line("abc", "de", positions = intArrayOf(0, 3))
        val measureChunk: (Int, String) -> Float = { chunk, text ->
            text.length * if (chunk == 0) 10f else 20f
        }
        val span = decorationSpanX(
            l,
            ReaderDecorationRun(3, 5, underlineMode = 1, highlight = false, markingId = "m1"),
            measureChunk,
        )
        assertEquals(100f, span!!.first)
        assertEquals(100f + 40f, span.second)

        // 右缘到字形末端为止：区间止于段边界（= 段 0 末字之后）时按段 0
        // 前缀宽收笔（30f），不吞段尾空白
        val endAtBoundary = decorationSpanX(
            l,
            ReaderDecorationRun(0, 3, underlineMode = 1, highlight = false, markingId = "m1"),
            measureChunk,
        )
        assertEquals(0f, endAtBoundary!!.first)
        assertEquals(30f, endAtBoundary.second)
    }
}
