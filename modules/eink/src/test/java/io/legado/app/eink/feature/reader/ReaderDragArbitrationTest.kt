package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读区拖动手势统一仲裁（横滑翻页 × 竖直下拉书签）的判据。
 *
 * 修复背景：此前两个独立检测器（detectHorizontalDragGestures ×
 * detectVerticalDragGestures）按各自轴向累计先过触摸 slop 者得手，
 * 竖直侧对方向与竖直优势均无门控——起手带下坠的横滑被书签检测器整笔
 * 抢走后，书签触发不了（需净下拉 80dp）、翻页检测器已取消，滑动被吞。
 * 统一仲裁对齐完整模式 ReaderCanvasSurface / PullBookmarkGesture：
 * 书签只在累计位移向下且 |Σy| > |Σx|×1.5 时认领，主导性翻转即交接
 * 翻页并锁存（本次手势不再回书签，防对角摇摆）。
 */
class ReaderDragArbitrationTest {

    private val slop = 8f

    private fun arbitrate(
        state: ReaderDragArbitrationState = ReaderDragArbitrationState(),
        x: Float,
        y: Float,
        bookmarkReady: Boolean = true,
    ): ReaderDragArbitrationState = ReaderDragArbitration.arbitrate(state, x, y, slop, bookmarkReady)

    @Test
    fun `未过 slop 的位移不参与仲裁`() {
        val state = arbitrate(x = 3f, y = 4f)
        assertEquals(ReaderDragOwner.PENDING, state.owner)
        assertFalse(state.pullLockedOut)
    }

    @Test
    fun `向下强竖直优势认领书签`() {
        assertEquals(ReaderDragOwner.PULL, arbitrate(x = 3f, y = 10f).owner)
    }

    @Test
    fun `书签认领后主导性翻转交接翻页并锁存`() {
        var state = arbitrate(x = 1f, y = 6f)
        assertEquals(ReaderDragOwner.PENDING, state.owner)
        state = arbitrate(state, x = 3f, y = 10f)
        assertEquals(ReaderDragOwner.PULL, state.owner)
        state = arbitrate(state, x = 20f, y = 12f)
        assertEquals(ReaderDragOwner.HORIZONTAL, state.owner)
        assertTrue(state.pullLockedOut)
    }

    @Test
    fun `书签释放但横移未过 slop 时回到待定并保持锁存`() {
        var state = arbitrate(x = 3f, y = 10f)
        state = arbitrate(state, x = 7f, y = 9f)
        assertEquals(ReaderDragOwner.PENDING, state.owner)
        assertTrue(state.pullLockedOut)
        // 锁存后竖直优势再强也不回书签
        state = arbitrate(state, x = 2f, y = 20f)
        assertEquals(ReaderDragOwner.PENDING, state.owner)
        state = arbitrate(state, x = 12f, y = 5f)
        assertEquals(ReaderDragOwner.HORIZONTAL, state.owner)
    }

    @Test
    fun `过 slop 首样本非候选即锁存`() {
        var state = arbitrate(x = 6f, y = 7f)
        assertEquals(ReaderDragOwner.PENDING, state.owner)
        assertTrue(state.pullLockedOut)
        state = arbitrate(state, x = 2f, y = 20f)
        assertEquals(ReaderDragOwner.PENDING, state.owner)
    }

    @Test
    fun `横向位移过 slop 即认领翻页`() {
        assertEquals(ReaderDragOwner.HORIZONTAL, arbitrate(x = 9f, y = 1f).owner)
    }

    @Test
    fun `候选与横向同时成立时书签优先`() {
        assertEquals(ReaderDragOwner.PULL, arbitrate(x = 10f, y = 20f).owner)
    }

    @Test
    fun `上滑与书签不可达都不认领`() {
        assertEquals(ReaderDragOwner.PENDING, arbitrate(x = 1f, y = -20f).owner)
        assertEquals(
            ReaderDragOwner.PENDING,
            arbitrate(x = 3f, y = 10f, bookmarkReady = false).owner,
        )
    }

    @Test
    fun `翻页认领后为终态`() {
        val state = arbitrate(
            ReaderDragArbitrationState(ReaderDragOwner.HORIZONTAL, pullLockedOut = true),
            x = 0f,
            y = 100f,
        )
        assertEquals(ReaderDragOwner.HORIZONTAL, state.owner)
    }

    @Test
    fun `下拉触发距离对齐完整模式 80dp`() {
        assertEquals(80, ReaderDragArbitration.PULL_TRIGGER_DISTANCE_DP)
    }

    @Test
    fun `过 slop 判定为累计位移的欧氏距离`() {
        assertFalse(ReaderDragArbitration.isPastSlop(3f, 4f, slop))
        assertTrue(ReaderDragArbitration.isPastSlop(0f, slop, slop))
    }
}
