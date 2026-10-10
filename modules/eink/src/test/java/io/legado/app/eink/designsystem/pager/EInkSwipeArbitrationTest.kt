package io.legado.app.eink.designsystem.pager

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E-Ink 分页手势统一仲裁（横滑切走 × 竖直翻页，松手定夺）的判据。
 *
 * 背景：两个独立轴向检测器（detectHorizontalDragGestures ×
 * detectVerticalDragGestures）按各自轴向先过触摸 slop 者得手，起手带
 * 下坠的横滑会被竖直检测器整笔抢走误翻页（同阅读页 ReaderDragArbitration
 * 修复前的症状）。统一仲裁在松手时按累计位移定夺：横向须严格主导才切走，
 * 竖直保持纯竖直路径的宽判。
 */
class EInkSwipeArbitrationTest {

    private val threshold = 48f

    private fun resolve(x: Float, y: Float): EInkSwipeDirection =
        EInkSwipeArbitration.resolve(x, y, threshold)

    @Test
    fun `横滑左右按累计位移符号分派`() {
        assertEquals(EInkSwipeDirection.LEFT, resolve(-60f, 2f))
        assertEquals(EInkSwipeDirection.RIGHT, resolve(60f, 2f))
    }

    @Test
    fun `未过阈值不动作`() {
        assertEquals(EInkSwipeDirection.NONE, resolve(40f, 0f))
        assertEquals(EInkSwipeDirection.NONE, resolve(0f, -40f))
        assertEquals(EInkSwipeDirection.NONE, resolve(0f, 0f))
    }

    @Test
    fun `横向不严格主导时让给竖直`() {
        // |Σx| == |Σy|：翻页是主操作，模糊斜向不切走
        // （此时 |Σy| ≥ 阈值必然成立，竖直宽判接管）
        assertEquals(EInkSwipeDirection.UP, resolve(60f, 60f))
        assertEquals(EInkSwipeDirection.DOWN, resolve(60f, -60f))
        // 竖直过阈值 → 翻页（宽判，与纯竖直路径行为一致）
        assertEquals(EInkSwipeDirection.UP, resolve(50f, 80f))
        assertEquals(EInkSwipeDirection.DOWN, resolve(50f, -80f))
    }

    @Test
    fun `横向略主导即切走`() {
        assertEquals(EInkSwipeDirection.LEFT, resolve(-80f, 70f))
        assertEquals(EInkSwipeDirection.RIGHT, resolve(80f, 70f))
    }

    @Test
    fun `横滑过阈但竖直更大时仍翻页`() {
        assertEquals(EInkSwipeDirection.UP, resolve(60f, 90f))
    }

    @Test
    fun `纯竖直滑动按方向翻页`() {
        assertEquals(EInkSwipeDirection.UP, resolve(0f, 60f))
        assertEquals(EInkSwipeDirection.DOWN, resolve(0f, -60f))
        // 轻微横向漂移不改变竖直判定
        assertEquals(EInkSwipeDirection.UP, resolve(10f, 60f))
    }
}
