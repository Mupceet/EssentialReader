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
 * 音量键翻页事件语义（纯函数）：阅读页与分页列表共用判定的锚定用例。
 * 返回三元组 = (是否消费, 上翻次数, 下翻次数)；未消费路径次数非零
 * 即为「放行却翻页」的越界副作用。常量取 android.view.KeyEvent
 * （编译期内联值，纯 JVM 可读）。
 */
class VolumePagingKeyEventTest {

    private fun event(
        action: Int = ACTION_DOWN,
        keyCode: Int = KEYCODE_VOLUME_DOWN,
        repeatCount: Int = 0,
        pagingEnabled: Boolean = true,
        modalPresent: Boolean = false,
    ): Triple<Boolean, Int, Int> {
        var up = 0
        var down = 0
        val consumed = volumePagingKeyEvent(
            action = action,
            keyCode = keyCode,
            repeatCount = repeatCount,
            pagingEnabled = pagingEnabled,
            modalPresent = modalPresent,
            onPageUp = { up += 1 },
            onPageDown = { down += 1 },
        )
        return Triple(consumed, up, down)
    }

    @Test
    fun `开关关闭放行系统且不翻页`() {
        assertEquals(Triple(false, 0, 0), event(pagingEnabled = false))
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
}
