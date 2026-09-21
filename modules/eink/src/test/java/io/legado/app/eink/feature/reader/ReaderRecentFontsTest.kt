package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字体配置弹层反显的最近字体记录：编解码。
 */
class ReaderRecentFontsTest {

    @Test
    fun `编码互逆`() {
        assertEquals(listOf("a", "b"), decodeRecentFontPaths(encodeRecentFontPaths(listOf("a", "b"))))
        assertEquals("a", encodeRecentFontPaths(listOf("a")))
    }

    @Test
    fun `空串解码为无历史`() {
        assertEquals(emptyList<String>(), decodeRecentFontPaths(""))
    }

    @Test
    fun `解码过滤空段容忍尾部分隔`() {
        assertEquals(listOf("a", "b"), decodeRecentFontPaths("a\n\nb\n"))
    }
}
