package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderTextStyleDefaultsTest {

    @Test
    fun `协商扩展字段默认全部为null即不跨桥写`() {
        val style = ReaderTextStyle()
        assertNull(style.bodyFont)
        assertNull(style.bodyWeight)
        assertNull(style.titleFont)
        assertNull(style.titleWeight)
        assertNull(style.titleMode)
        assertNull(style.titleSize)
        assertNull(style.titleTopSpacing)
        assertNull(style.titleBottomSpacing)
        assertNull(style.titleLineSpacing)
        assertNull(style.headerFont)
        assertNull(style.headerMode)
        assertNull(style.headerSize)
        assertNull(style.headerDivider)
        assertNull(style.footerVisible)
        assertNull(style.footerDivider)
    }

    @Test
    fun `存量字段默认值不变`() {
        val style = ReaderTextStyle()
        assertEquals(20, style.textSize)
        assertEquals(2, style.indentChars)
        assertEquals(12, style.lineSpacing)
        assertEquals(16, style.paddingLeft)
    }
}
