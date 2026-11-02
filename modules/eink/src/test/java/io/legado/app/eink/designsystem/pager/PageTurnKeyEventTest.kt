package io.legado.app.eink.designsystem.pager

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_MULTIPLE
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 翻页按键事件语义（纯函数）：自定义翻页键 + 音量键共用判定的锚定
 * 用例。返回三元组 = (是否消费, 上翻次数, 下翻次数)；未消费路径次数
 * 非零即为「放行却翻页」的越界副作用。常量取 android.view.KeyEvent
 * （编译期内联值，纯 JVM 可读）。
 */
class PageTurnKeyEventTest {

    private fun event(
        action: Int = ACTION_DOWN,
        keyCode: Int = KEYCODE_VOLUME_DOWN,
        repeatCount: Int = 0,
        volumePagingEnabled: Boolean = true,
        prevKeyCodes: Set<Int> = emptySet(),
        nextKeyCodes: Set<Int> = emptySet(),
        modalPresent: Boolean = false,
    ): Triple<Boolean, Int, Int> {
        var up = 0
        var down = 0
        val consumed = pagingKeyEvent(
            action = action,
            keyCode = keyCode,
            repeatCount = repeatCount,
            volumePagingEnabled = volumePagingEnabled,
            prevKeyCodes = prevKeyCodes,
            nextKeyCodes = nextKeyCodes,
            modalPresent = modalPresent,
            onPageUp = { up += 1 },
            onPageDown = { down += 1 },
        )
        return Triple(consumed, up, down)
    }

    // —— 音量键语义（原 VolumePagingKeyEventTest 全量保留）——

    @Test
    fun `开关关闭放行系统且不翻页`() {
        assertEquals(Triple(false, 0, 0), event(volumePagingEnabled = false))
    }

    @Test
    fun `模态弹框在场放行系统且不翻页`() {
        assertEquals(Triple(false, 0, 0), event(modalPresent = true))
    }

    @Test
    fun `音量上首按翻上一页并消费`() {
        assertEquals(Triple(true, 1, 0), event(keyCode = KEYCODE_VOLUME_UP))
    }

    @Test
    fun `音量下首按翻下一页并消费`() {
        assertEquals(Triple(true, 0, 1), event(keyCode = KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `长按重复不翻但仍消费`() {
        assertEquals(Triple(true, 0, 0), event(keyCode = KEYCODE_VOLUME_UP, repeatCount = 3))
        assertEquals(Triple(true, 0, 0), event(keyCode = KEYCODE_VOLUME_DOWN, repeatCount = 1))
    }

    @Test
    fun `抬起吞掉音量键保证按键对整体被消费`() {
        assertEquals(Triple(true, 0, 0), event(action = ACTION_UP, keyCode = KEYCODE_VOLUME_UP))
        assertEquals(Triple(true, 0, 0), event(action = ACTION_UP, keyCode = KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `非音量键一律放行`() {
        assertEquals(Triple(false, 0, 0), event(keyCode = KEYCODE_A))
        assertEquals(Triple(false, 0, 0), event(action = ACTION_UP, keyCode = KEYCODE_A))
        assertEquals(Triple(false, 0, 0), event(action = ACTION_MULTIPLE))
    }

    // —— 自定义键扩展语义 ——

    @Test
    fun `自定义上一页键命中翻页并消费`() {
        assertEquals(Triple(true, 1, 0), event(keyCode = 92, prevKeyCodes = setOf(92)))
    }

    @Test
    fun `自定义下一页键命中翻页并消费`() {
        assertEquals(Triple(true, 0, 1), event(keyCode = 93, nextKeyCodes = setOf(93)))
    }

    @Test
    fun `自定义键不受音量开关控制`() {
        assertEquals(Triple(true, 1, 0), event(keyCode = 92, volumePagingEnabled = false, prevKeyCodes = setOf(92)))
    }

    @Test
    fun `自定义键优先于音量键`() {
        // 音量+ 被录为自定义下一页键：按自定义方向翻页（对齐宿主完整模式判定优先级）
        assertEquals(Triple(true, 0, 1), event(keyCode = KEYCODE_VOLUME_UP, nextKeyCodes = setOf(KEYCODE_VOLUME_UP)))
    }

    @Test
    fun `自定义键长按重复不翻但仍消费`() {
        assertEquals(Triple(true, 0, 0), event(keyCode = 92, repeatCount = 2, prevKeyCodes = setOf(92)))
    }

    @Test
    fun `自定义键抬起吞掉`() {
        assertEquals(Triple(true, 0, 0), event(action = ACTION_UP, keyCode = 92, prevKeyCodes = setOf(92)))
    }

    @Test
    fun `模态在场自定义键同样放行`() {
        assertEquals(Triple(false, 0, 0), event(keyCode = 92, prevKeyCodes = setOf(92), modalPresent = true))
    }

    @Test
    fun `音量开关关闭时音量键放行但自定义键仍生效`() {
        assertEquals(Triple(false, 0, 0), event(keyCode = KEYCODE_VOLUME_UP, volumePagingEnabled = false))
        assertEquals(Triple(true, 1, 0), event(keyCode = 92, volumePagingEnabled = false, prevKeyCodes = setOf(92)))
    }
}
