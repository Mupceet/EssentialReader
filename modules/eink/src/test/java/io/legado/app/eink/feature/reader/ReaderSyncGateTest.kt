package io.legado.app.eink.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端同步触发门槛状态机（复刻宿主 justInitData / 装载完成同步位语义）。
 */
class ReaderSyncGateTest {

    @Test
    fun `fresh entry arms entry sync and opens initial window`() {
        val gate = ReaderSyncGate()
        gate.onFreshEntryStarted()
        assertTrue(gate.consumeEntrySync())
    }

    @Test
    fun `entry sync is one-shot`() {
        val gate = ReaderSyncGate()
        gate.onFreshEntryStarted()
        assertTrue(gate.consumeEntrySync())
        assertFalse(gate.consumeEntrySync())
    }

    @Test
    fun `network sync is blocked during initial window`() {
        val gate = ReaderSyncGate()
        gate.onFreshEntryStarted()
        gate.consumeEntrySync()
        assertFalse(gate.allowNetworkSync())
    }

    @Test
    fun `pause closes initial window and releases network sync`() {
        val gate = ReaderSyncGate()
        gate.onFreshEntryStarted()
        gate.consumeEntrySync()
        gate.onPaused()
        assertTrue(gate.allowNetworkSync())
    }

    @Test
    fun `network sync is allowed when never armed`() {
        val gate = ReaderSyncGate()
        assertTrue(gate.allowNetworkSync())
    }

    @Test
    fun `re-arm only on fresh entry`() {
        val gate = ReaderSyncGate()
        gate.onFreshEntryStarted()
        gate.consumeEntrySync()
        gate.onPaused()
        // 重挂载（返回自目录/换源）不重新武装——宿主 InitData 仅每 VM 一次
        assertFalse(gate.consumeEntrySync())
    }
}
