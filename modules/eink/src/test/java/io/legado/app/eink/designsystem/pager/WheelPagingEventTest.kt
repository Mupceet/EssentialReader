package io.legado.app.eink.designsystem.pager

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 滚轮翻页事件语义（纯函数）锚定用例。返回三元组 = (是否消费, 上翻
 * 次数, 下翻次数)。方向对齐宿主完整模式：下滚（负值）= 下一页。
 */
class WheelPagingEventTest {

    private fun wheel(
        axisValue: Float,
        pagingEnabled: Boolean = true,
        modalPresent: Boolean = false,
    ): Triple<Boolean, Int, Int> {
        var up = 0
        var down = 0
        val consumed = wheelPagingEvent(
            axisValue = axisValue,
            pagingEnabled = pagingEnabled,
            modalPresent = modalPresent,
            onPageUp = { up += 1 },
            onPageDown = { down += 1 },
        )
        return Triple(consumed, up, down)
    }

    @Test
    fun `下滚翻下一页并消费`() {
        assertEquals(Triple(true, 0, 1), wheel(-1f))
    }

    @Test
    fun `上滚翻上一页并消费`() {
        assertEquals(Triple(true, 1, 0), wheel(1f))
    }

    @Test
    fun `零位移不消费`() {
        assertEquals(Triple(false, 0, 0), wheel(0f))
    }

    @Test
    fun `开关关闭放行且不翻页`() {
        assertEquals(Triple(false, 0, 0), wheel(-1f, pagingEnabled = false))
    }

    @Test
    fun `模态在场放行且不翻页`() {
        assertEquals(Triple(false, 0, 0), wheel(-1f, modalPresent = true))
    }
}
