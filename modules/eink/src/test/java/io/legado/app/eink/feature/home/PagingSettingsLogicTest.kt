package io.legado.app.eink.feature.home

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页设置页纯逻辑锚定用例：录入迁移/拒录、BACK 退出录入、录入态
 * 全量消费、键名映射。
 */
class PagingSettingsLogicTest {

    // —— recordPageKey ——

    @Test
    fun `录入上一页键`() {
        assertEquals(
            setOf(92) to emptySet<Int>(),
            recordPageKey(emptySet(), emptySet(), PagingDirection.PREV, 92),
        )
    }

    @Test
    fun `同向重复录入幂等`() {
        assertEquals(
            setOf(92) to emptySet<Int>(),
            recordPageKey(setOf(92), emptySet(), PagingDirection.PREV, 92),
        )
    }

    @Test
    fun `录入时自动从另一方向移除`() {
        // 93 原是上一页键，录为下一页后从上一页键集迁出
        assertEquals(
            emptySet<Int>() to setOf(93),
            recordPageKey(setOf(93), emptySet(), PagingDirection.NEXT, 93),
        )
    }

    @Test
    fun `非正键码拒录`() {
        assertNull(recordPageKey(emptySet(), emptySet(), PagingDirection.PREV, 0))
        assertNull(recordPageKey(emptySet(), emptySet(), PagingDirection.NEXT, -1))
    }

    // —— handleCaptureKey ——

    @Test
    fun `BACK 按下退出录入并消费`() {
        var exited = false
        val consumed = handleCaptureKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BACK,
            repeatCount = 0,
            direction = PagingDirection.PREV,
            prevKeyCodes = emptySet(),
            nextKeyCodes = emptySet(),
            onRecorded = { _, _ -> error("BACK 不应录入") },
            onExitCapture = { exited = true },
        )
        assertTrue(consumed)
        assertTrue(exited)
    }

    @Test
    fun `有效键按下经回调录入并消费`() {
        var recorded: Pair<Set<Int>, Set<Int>>? = null
        val consumed = handleCaptureKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = 92,
            repeatCount = 0,
            direction = PagingDirection.PREV,
            prevKeyCodes = emptySet(),
            nextKeyCodes = setOf(92),
            onRecorded = { prev, next -> recorded = prev to next },
            onExitCapture = { error("有效键不应退出录入") },
        )
        assertTrue(consumed)
        assertEquals(setOf(92) to emptySet<Int>(), recorded)
    }

    @Test
    fun `非正键码按下静默忽略但仍消费`() {
        val consumed = handleCaptureKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = 0,
            repeatCount = 0,
            direction = PagingDirection.PREV,
            prevKeyCodes = emptySet(),
            nextKeyCodes = emptySet(),
            onRecorded = { _, _ -> error("无效键不应录入") },
            onExitCapture = { error("无效键不应退出录入") },
        )
        assertTrue(consumed)
    }

    @Test
    fun `录入态长按重复与抬起全消费`() {
        val exit = { }
        val none: (Set<Int>, Set<Int>) -> Unit = { _, _ -> }
        assertTrue(
            handleCaptureKey(KeyEvent.ACTION_DOWN, 92, 2, PagingDirection.PREV, emptySet(), emptySet(), none, exit),
        )
        assertTrue(
            handleCaptureKey(KeyEvent.ACTION_UP, 92, 0, PagingDirection.PREV, emptySet(), emptySet(), none, exit),
        )
    }

    // —— pageKeyDisplayName ——

    @Test
    fun `常用键名映射带键码`() {
        assertEquals("音量上 24", pageKeyDisplayName(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals("上翻页 92", pageKeyDisplayName(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals("方向下 20", pageKeyDisplayName(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `冷门键回落键码`() {
        assertEquals("键码 304", pageKeyDisplayName(304))
    }
}
