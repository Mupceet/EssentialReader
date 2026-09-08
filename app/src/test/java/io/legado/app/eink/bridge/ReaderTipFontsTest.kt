package io.legado.app.eink.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderTipFontsTest {

    @Test
    fun `页眉设置字体文件则用之`() {
        assertEquals(
            TipFont.File("/h.ttf"),
            resolveHeaderTipFont("/h.ttf", "/b.ttf", 0),
        )
    }

    @Test
    fun `页眉未设置跟随正文字体`() {
        assertEquals(
            TipFont.File("/b.ttf"),
            resolveHeaderTipFont("", "/b.ttf", 0),
        )
    }

    @Test
    fun `正文系统预设时页眉跟随预设`() {
        assertEquals(TipFont.Preset(1), resolveHeaderTipFont("", "", 1))
        assertEquals(TipFont.Preset(2), resolveHeaderTipFont("", "", 2))
    }

    @Test
    fun `都未设置返回null即系统默认`() {
        assertNull(resolveHeaderTipFont("", "", 0))
    }

    @Test
    fun `页脚默认沿用页眉解析`() {
        val header = resolveHeaderTipFont("/h.ttf", "/b.ttf", 0)
        assertEquals(
            header,
            resolveFooterTipFont("/f.ttf", applyHeaderStyle = true, headerResolved = header, textFont = "/b.ttf", systemTypefaces = 0),
        )
    }

    @Test
    fun `页脚独立模式按同规则解析`() {
        assertEquals(
            TipFont.File("/f.ttf"),
            resolveFooterTipFont("/f.ttf", applyHeaderStyle = false, headerResolved = null, textFont = "/b.ttf", systemTypefaces = 0),
        )
        assertEquals(
            TipFont.File("/b.ttf"),
            resolveFooterTipFont("", applyHeaderStyle = false, headerResolved = null, textFont = "/b.ttf", systemTypefaces = 0),
        )
    }
}
