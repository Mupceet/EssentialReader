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
    fun `翻页只记录方向不改被拖端与端点值`() {
        // 手指抓起后全程拖同一端：翻页方向只决定吸附哪条边，不换侧
        val forward = session(start = 10, end = 30).withFlip(forward = true)
        assertTrue(forward.draggingEnd)
        assertTrue(forward.lastFlipForward)
        assertEquals(10, forward.startPos)
        assertEquals(30, forward.endPos)

        val backward = forward.withFlip(forward = false)
        assertTrue(backward.draggingEnd)
        assertFalse(backward.lastFlipForward)
        assertEquals(10, backward.startPos)
        assertEquals(30, backward.endPos)
    }

    @Test
    fun `下翻再翻回后拖过起点区间收敛到起点侧`() {
        // 真机场景：N 页 X 处起选 → 下翻到 N+1 选到底 → 拖回页顶翻回 N →
        // 继续拖到 X-10：期望 [X-10, X]（与页内反向拖动一致），而不是拖到 N+1 那一头
        var s = session(start = 100, end = 120)                       // X = 100
        s = s.withFlip(forward = true)                                // 下翻
        s = s.moveDragged(200)                                        // 吸附 N+1 首行（接缝）
        assertEquals(100, s.startPos)                                 // 固定端仍是 X
        s = s.moveDragged(260)                                        // 在 N+1 拖到底
        assertEquals(260, s.endPos)
        s = s.withFlip(forward = false)                               // 拖回页顶翻回 N
        s = s.moveDragged(199)                                        // 吸附 N 末行（接缝）
        assertEquals(100, s.startPos)
        assertEquals(199, s.endPos)
        s = s.moveDragged(101)                                        // 往回收
        assertEquals(101, s.endPos)
        s = s.moveDragged(90)                                         // 越过起点 X
        assertEquals(90, s.startPos)
        assertEquals(100, s.endPos)
        assertFalse(s.draggingEnd)
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
