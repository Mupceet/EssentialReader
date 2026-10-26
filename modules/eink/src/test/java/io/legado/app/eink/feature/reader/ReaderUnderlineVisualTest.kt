package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 下划线 mode → 绘制形态映射锚定。
 *
 * mode 6（删除线）/ 7（荧光半高带）此前误入「行底实线」分支，本映射
 * 以枚举显式锚定语义，防止回归（完整模式参照 ReaderPageDecorationDrawCache）。
 */
class ReaderUnderlineVisualTest {

    @Test
    fun `mode 到形态映射锚定`() {
        assertEquals(ReaderUnderlineVisual.SOLID, underlineVisualOf(1))
        assertEquals(ReaderUnderlineVisual.DASHED, underlineVisualOf(2))
        assertEquals(ReaderUnderlineVisual.WAVE, underlineVisualOf(3))
        assertEquals(ReaderUnderlineVisual.DOUBLE, underlineVisualOf(4))
        // 5 SVG 花色：模块明示不支持，降级实线
        assertEquals(ReaderUnderlineVisual.SOLID, underlineVisualOf(5))
        assertEquals(ReaderUnderlineVisual.STRIKE_THROUGH, underlineVisualOf(6))
        assertEquals(ReaderUnderlineVisual.HALF_BAND, underlineVisualOf(7))
        // 未知值与 0 同样不进下划线分支（0 在绘制侧已跳过）
        assertEquals(ReaderUnderlineVisual.SOLID, underlineVisualOf(8))
        assertEquals(ReaderUnderlineVisual.SOLID, underlineVisualOf(0))
    }
}
