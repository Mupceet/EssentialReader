package io.legado.app.eink.designsystem.pager

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自定义翻页键码串编解码锚定用例：空串/空格/非法段/非正键码/重复/
 * 顺序保持（格式化不重排，录入顺序即展示顺序）。
 */
class PageKeyCodesTest {

    @Test
    fun `空串解析为空集`() {
        assertEquals(emptySet<Int>(), parseKeyCodes(""))
    }

    @Test
    fun `常规逗号串解析为键码集`() {
        assertEquals(setOf(92, 93), parseKeyCodes("92,93"))
    }

    @Test
    fun `段内空格容错`() {
        assertEquals(setOf(24, 25), parseKeyCodes(" 24 , 25 "))
    }

    @Test
    fun `非法段丢弃不抛异常`() {
        assertEquals(setOf(92), parseKeyCodes("92,abc,,x1"))
    }

    @Test
    fun `非正键码丢弃`() {
        assertEquals(emptySet<Int>(), parseKeyCodes("0,-1"))
    }

    @Test
    fun `重复键码去重`() {
        assertEquals(setOf(92), parseKeyCodes("92,92"))
    }

    @Test
    fun `格式化保持入参顺序`() {
        assertEquals("93,92", formatKeyCodes(listOf(93, 92)))
    }

    @Test
    fun `空集格式化为空串`() {
        assertEquals("", formatKeyCodes(emptySet()))
    }

    @Test
    fun `编解码往返一致`() {
        val encoded = formatKeyCodes(setOf(19, 20))
        assertEquals(setOf(19, 20), parseKeyCodes(encoded))
    }
}
