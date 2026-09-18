package io.legado.app.eink.app

import org.junit.Assert.assertEquals
import org.junit.Test

class EInkAppUpdateProgressTest {

    @Test
    fun `marker offset maps percent onto remaining track`() {
        // 轨道 300px，牌宽 40px，可移动余量 260px
        assertEquals(0, progressMarkerOffset(300, 40, 0))
        assertEquals(130, progressMarkerOffset(300, 40, 50))
        assertEquals(260, progressMarkerOffset(300, 40, 100))
    }

    @Test
    fun `marker offset clamps out of range percent`() {
        assertEquals(0, progressMarkerOffset(300, 40, -10))
        assertEquals(260, progressMarkerOffset(300, 40, 150))
    }

    @Test
    fun `marker wider than track pins to start`() {
        assertEquals(0, progressMarkerOffset(100, 160, 80))
    }
}
