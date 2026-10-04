package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 简繁转换档位文案映射：入口行行尾值与单选弹窗选项共用的唯一文案源
 * （0 关闭 / 1 繁体转简体 / 2 简体转繁体），未识别值回落「关闭」。
 */
class ReaderChineseConverterTest {

    @Test
    fun `档位文案映射`() {
        assertEquals("关闭", chineseConverterTypeLabel(0))
        assertEquals("繁体转简体", chineseConverterTypeLabel(1))
        assertEquals("简体转繁体", chineseConverterTypeLabel(2))
    }

    @Test
    fun `未识别值回落关闭`() {
        assertEquals("关闭", chineseConverterTypeLabel(-1))
        assertEquals("关闭", chineseConverterTypeLabel(3))
        assertEquals("关闭", chineseConverterTypeLabel(Int.MAX_VALUE))
    }
}
