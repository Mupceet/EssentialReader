package io.legado.app.eink.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 与宿主完整模式 resolveAppFontScale 同语义：设置值 ÷10 为倍率，
 * 有效区间 0.8~1.6，越界回落系统缩放（不截断）。
 */
class ResolveEInkFontScaleTest {

    @Test
    fun `setting divided by ten within range`() {
        assertEquals(1.0f, resolveEInkFontScale(10, 1.3f))
        assertEquals(1.1f, resolveEInkFontScale(11, 1.3f))
        assertEquals(0.8f, resolveEInkFontScale(8, 1.0f))
        assertEquals(1.6f, resolveEInkFontScale(16, 1.0f))
    }

    @Test
    fun `out of range falls back to system scale`() {
        assertEquals(1.3f, resolveEInkFontScale(7, 1.3f))
        assertEquals(1.0f, resolveEInkFontScale(17, 1.0f))
        assertEquals(1.0f, resolveEInkFontScale(0, 1.0f))
        assertEquals(1.45f, resolveEInkFontScale(79, 1.45f))
    }
}
