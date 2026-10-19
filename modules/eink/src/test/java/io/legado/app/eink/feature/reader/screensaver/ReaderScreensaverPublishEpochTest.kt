package io.legado.app.eink.feature.reader.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程级发布代数语义：最新调度者胜出，旧 token 在新调度发生后失效。
 * 对应修复：旧阅读组合退出的慢 flush 不得覆盖新组合已发布的锁屏图。
 */
class ReaderScreensaverPublishEpochTest {

    @Test
    fun `首次调度即当前`() {
        val epoch = ReaderScreensaverPublishEpoch()
        val token = epoch.schedule()
        assertTrue(epoch.isCurrent(token))
    }

    @Test
    fun `新调度使旧 token 失效且自身为当前`() {
        val epoch = ReaderScreensaverPublishEpoch()
        val first = epoch.schedule()
        val second = epoch.schedule()
        assertFalse(epoch.isCurrent(first))
        assertTrue(epoch.isCurrent(second))
    }

    @Test
    fun `代数单调递增不回退`() {
        val epoch = ReaderScreensaverPublishEpoch()
        val tokens = List(5) { epoch.schedule() }
        assertEquals(tokens, tokens.sorted())
        assertEquals(tokens.last().let { epoch.isCurrent(it) }, true)
    }

    @Test
    fun `多实例场景旧组合 flush token 被新组合调度超越`() {
        // 两个 ReaderScreensaverPublisher 实例共享同一个进程级代数：
        // A 调度（token=a）→ B 调度（token=b）→ A 的 flush 复核 isCurrent(a) 必须为 false
        val shared = ReaderScreensaverPublishEpoch()
        val tokenA = shared.schedule()
        val tokenB = shared.schedule()
        assertFalse("A 的 flush 必须被 B 的调度超越", shared.isCurrent(tokenA))
        assertTrue(shared.isCurrent(tokenB))
    }
}
