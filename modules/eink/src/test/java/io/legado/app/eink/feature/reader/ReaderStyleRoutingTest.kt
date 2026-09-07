package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.FallbackReaderStyleCatalog
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleRoutingTest {

    private val catalog = FallbackReaderStyleCatalog.create()

    @Test
    fun `无变更不重排`() {
        val style = ReaderTextStyle()
        assertFalse(styleChangeNeedsRelayout(catalog, style, style))
    }

    @Test
    fun `页眉左右边距变更不重排`() {
        val old = ReaderTextStyle()
        val new = old.copy(headerPaddingLeft = 20)
        assertFalse(styleChangeNeedsRelayout(catalog, old, new))
    }

    @Test
    fun `页眉上下边距变更需重排`() {
        val old = ReaderTextStyle()
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(headerPaddingTop = 4)))
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(footerPaddingBottom = 8)))
    }

    @Test
    fun `正文字号与边距变更需重排`() {
        val old = ReaderTextStyle()
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(textSize = 22)))
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(paddingLeft = 20)))
    }

    @Test
    fun `目录缺失的参数默认需要重排`() {
        val old = ReaderTextStyle()
        // 标题字号不在回落目录——保守按需重排处理
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(titleSize = 30)))
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(bodyFont = ReaderFontSelection.Serif)))
    }
}
