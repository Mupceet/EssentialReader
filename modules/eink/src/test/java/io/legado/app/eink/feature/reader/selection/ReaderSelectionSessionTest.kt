package io.legado.app.eink.feature.reader.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨页续选会话（章内位置为真值）：端点可增可减、越界交换侧别、文本累计
 * 与端点解耦。
 */
class ReaderSelectionSessionTest {

    private fun session(
        start: Int,
        end: Int,
        draggingEnd: Boolean = true,
        includesTitle: Boolean = false,
    ) = ReaderSelectionSession(
        startPos = start,
        endPos = end,
        draggingEnd = draggingEnd,
        includesTitle = includesTitle,
    )

    @Test
    fun `拖被拖端点直接赋值可增可减`() {
        // 翻页后被拖端点落在新页末行（整页），手指抬上去必须能收回来
        val expanded = session(start = 10, end = 30).moveDragged(80)
        assertEquals(10, expanded.startPos)
        assertEquals(80, expanded.endPos)
        assertTrue(expanded.draggingEnd)

        val shrunk = expanded.moveDragged(45)
        assertEquals(10, shrunk.startPos)
        assertEquals(45, shrunk.endPos)
        assertTrue(shrunk.draggingEnd)
    }

    @Test
    fun `拖起始端同样可增可减`() {
        val moved = session(start = 10, end = 30, draggingEnd = false).moveDragged(25)
        assertEquals(25, moved.startPos)
        assertEquals(30, moved.endPos)
        assertFalse(moved.draggingEnd)
    }

    @Test
    fun `被拖端点越过对端交换两端并翻转侧别`() {
        val crossed = session(start = 10, end = 30).moveDragged(5)
        assertEquals(5, crossed.startPos)
        assertEquals(10, crossed.endPos)
        // 手指那一端现在是区间的起点侧，继续拖动仍作用在它上面
        assertFalse(crossed.draggingEnd)
        val again = crossed.moveDragged(1)
        assertEquals(1, again.startPos)
        assertEquals(10, again.endPos)
    }

    @Test
    fun `页段并入不改变端点`() {
        val merged = session(start = 10, end = 30).withSegments(
            listOf(ReaderSelectionSegment("段", 10, 30)),
        )
        assertEquals(10, merged.startPos)
        assertEquals(30, merged.endPos)
        assertEquals(1, merged.segments.size)
    }

    @Test
    fun `每次翻页按方向重置被拖端且不动端点值`() {
        // 会话内双向：先下翻（拖结束端），再反向拖出页顶（改拖起始端）
        val forward = session(start = 10, end = 30).withFlipDirection(forward = true)
        assertTrue(forward.draggingEnd)
        assertEquals(10, forward.startPos)
        assertEquals(30, forward.endPos)

        val backward = forward.withFlipDirection(forward = false)
        assertFalse(backward.draggingEnd)
        // 只换侧、不动值——否则翻页边吸附会落到另一端（真机反馈的那条）
        assertEquals(10, backward.startPos)
        assertEquals(30, backward.endPos)
        // 换侧后继续拖动作用在起始端
        val extended = backward.moveDragged(4)
        assertEquals(4, extended.startPos)
        assertEquals(30, extended.endPos)
    }

    @Test
    fun `由页内选区建会话按下翻方向定被拖侧`() {
        val ui = ReaderSelectionUi(
            startHit = ReaderTextHit(0, 2),
            endHit = ReaderTextHit(1, 3),
            selectedText = "窗前\n看着",
            bodyStart = 2,
            bodyEnd = 9,
            includesTitle = false,
        )
        val forward = ReaderSelectionSession.from(ui, draggingEnd = true)
        assertEquals(2, forward.startPos)
        assertEquals(9, forward.endPos)
        assertTrue(forward.draggingEnd)
        assertFalse(forward.includesTitle)
    }
}
