package io.legado.app.eink.app

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 滚轮枢纽（栈式分发 + 200ms 冷却）锚定用例：时间源注入假时钟，
 * 冷却只在处理器接受后起算，拒绝（翻页关闭）不消费不起冷却。
 */
class EInkWheelHubTest {

    private var now = 0L

    private fun hub() = EInkWheelHub(timeSource = { now })

    @Test
    fun `非滚动动作与零位移放行`() {
        val hub = hub()
        hub.register { true }
        assertEquals(false, hub.dispatchScroll(MotionEvent.ACTION_HOVER_MOVE, -1f))
        assertEquals(false, hub.dispatchScroll(MotionEvent.ACTION_SCROLL, 0f))
    }

    @Test
    fun `空栈放行`() {
        assertEquals(false, hub().dispatchScroll(MotionEvent.ACTION_SCROLL, -1f))
    }

    @Test
    fun `栈顶拒绝时放行且不起冷却`() {
        val hub = hub()
        var handled = 0
        hub.register { handled += 1; false }
        assertEquals(false, hub.dispatchScroll(MotionEvent.ACTION_SCROLL, -1f))
        now += 10
        assertEquals(
            "处理器拒绝不起冷却，后续事件仍达处理器",
            false,
            hub.dispatchScroll(MotionEvent.ACTION_SCROLL, -1f),
        )
        assertEquals(2, handled)
    }

    @Test
    fun `接受后冷却期内消费但忽略`() {
        val hub = hub()
        var handled = 0
        hub.register { handled += 1; true }
        assertEquals(true, hub.dispatchScroll(MotionEvent.ACTION_SCROLL, -1f))
        assertEquals(1, handled)
        now += 100 // 冷却窗口内
        assertEquals("冷却期内消费但忽略", true, hub.dispatchScroll(MotionEvent.ACTION_SCROLL, -1f))
        assertEquals(1, handled)
        now += 101 // 累计 201ms，出冷却
        assertEquals(true, hub.dispatchScroll(MotionEvent.ACTION_SCROLL, -1f))
        assertEquals(2, handled)
    }

    @Test
    fun `注册入栈注销弹栈`() {
        val hub = hub()
        assertEquals(0, hub.handlerCount)
        val unregister = hub.register { true }
        assertEquals(1, hub.handlerCount)
        unregister()
        assertEquals("注销后栈空（滚轮放行系统）", 0, hub.handlerCount)
    }
}
