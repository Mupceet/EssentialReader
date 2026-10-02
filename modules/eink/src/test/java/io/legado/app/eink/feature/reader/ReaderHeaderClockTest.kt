package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderHeaderClockTest {

    // --- 页眉时钟分钟对齐：不翻页也按分钟刷新时间/电量 ---

    @Test
    fun `半分时刻延迟到下一分钟`() {
        assertEquals(30_000L, millisUntilNextMinute(t(0, 30_000)))
        assertEquals(1L, millisUntilNextMinute(t(0, 59_999)))
        assertEquals(29_999L, millisUntilNextMinute(t(0, 30_001)))
    }

    @Test
    fun `整分边界不零延迟跳到下一分钟`() {
        // 恰在分钟边界（毫秒为 0）时不立即触发，等满下一分钟——
        // 零延迟会造成连续两次刷新且显示同一分钟。
        assertEquals(60_000L, millisUntilNextMinute(t(5, 0)))
    }

    @Test
    fun `时钟回拨或异常输入被钳制在分钟窗口内`() {
        // epoch 0 是分钟边界：与整分语义一致等满下一分钟
        assertEquals(60_000L, millisUntilNextMinute(0L))
        // 负时间戳（回拨）：差值可能越界，钳到至少 1ms、至多 60s
        val delayed = millisUntilNextMinute(-1_000L)
        assert(delayed in 1L..60_000L)
    }

    private fun t(minute: Long, millis: Long): Long = minute * 60_000L + millis
}
