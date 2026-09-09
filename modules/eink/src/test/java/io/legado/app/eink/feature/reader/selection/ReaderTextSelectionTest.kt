package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 假等宽测量：每字符 10px，便于手算。 */
private val measure: (String) -> Float = { it.length * 10f }

private fun line(
    vararg chunks: String,
    positions: IntArray,
    baseY: Float = 50f,
    top: Float = 30f,
    bottom: Float = 70f,
    isTitle: Boolean = false,
): ReaderPageLine = ReaderPageLine(
    baseY = baseY, isTitle = isTitle, chunks = chunks.toList(),
    x = FloatArray(chunks.size) { i -> i * 100f },
    chapterPositions = positions, top = top, bottom = bottom,
)

private fun snapshot(vararg lines: ReaderPageLine) = ReaderPageSnapshot(
    title = "章", readProgress = "1/1",
    titleSpec = ReaderPaintSpec(20f, 0f, null, null),
    contentSpec = ReaderPaintSpec(20f, 0f, null, null),
    lines = lines.toList(), images = emptyList(),
)

class ReaderTextSelectionTest {

    @Test
    fun `命中测试按行盒定行按前缀宽度定字符`() {
        val snap = snapshot(line("abcdef", positions = intArrayOf(0)))
        // 行内 x=25 = 第 2 字符（span [20,30)）中线 → 命中字符 2
        assertEquals(ReaderTextHit(0, 2), hitTest(snap, x = 25f, y = 50f, measure = measure))
        // 行盒外
        assertNull(hitTest(snap, x = 25f, y = 200f, measure = measure))
    }

    @Test
    fun `命中交换端点后区间规范有序`() {
        val a = ReaderTextHit(0, 5)
        val b = ReaderTextHit(2, 1)
        val (start, end) = normalizeHits(a, b)
        assertEquals(ReaderTextHit(0, 5), start)
        assertEquals(ReaderTextHit(2, 1), end)
    }

    @Test
    fun `区间构建跨行拼接文本并计入正文间隙`() {
        val snap = snapshot(
            line("第一段落", positions = intArrayOf(0)),
            line("续行", positions = intArrayOf(4)),      // 软换行：gap=0
            line("新段落", positions = intArrayOf(7)),      // 段落间隙：gap=1（\n）
        )
        val sel = buildSelection(
            snap, ReaderTextHit(0, 2), ReaderTextHit(2, 1),
        )!!
        assertEquals("段落续行\n新", sel.selectedText)
        assertEquals(2, sel.bodyStart)
        assertEquals(8, sel.bodyEnd)
        assertFalse(sel.includesTitle)
    }

    @Test
    fun `含标题行选区标记 includesTitle 且正文区间取正文行`() {
        val snap = snapshot(
            line("标题", positions = intArrayOf(100), isTitle = true),
            line("正文内容", positions = intArrayOf(0)),
        )
        val sel = buildSelection(
            snap, ReaderTextHit(0, 0), ReaderTextHit(1, 2),
        )!!
        assertTrue(sel.includesTitle)
        assertEquals(0, sel.bodyStart)
        assertEquals(2, sel.bodyEnd)
        assertEquals("标题\n正文", sel.selectedText)
    }

    @Test
    fun `纯标题选区正文区间退化为零`() {
        val snap = snapshot(line("标题", positions = intArrayOf(100), isTitle = true))
        val sel = buildSelection(snap, ReaderTextHit(0, 0), ReaderTextHit(0, 2))!!
        assertTrue(sel.includesTitle)
        assertEquals(0, sel.bodyStart)
        assertEquals(0, sel.bodyEnd)
        assertEquals("标题", sel.selectedText)
    }
}
