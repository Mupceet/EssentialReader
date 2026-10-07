package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderTextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 统一字体写径：正文直选、标题/页眉归位「跟随正文」——阅读器字体配置
 * 弹层（setReaderFont）与独立字体设置页（FontSettingsScreen）共用的
 * 写径纯函数。
 */
class ReaderUnifiedFontTest {

    @Test
    fun `文件字体正文直选且标题页眉跟随正文`() {
        val style = ReaderTextStyle(
            bodyFont = ReaderFontSelection.Sans,
            titleFont = ReaderFontSelection.Sans,
            headerFont = ReaderFontSelection.Sans,
        )
        val unified = style.withUnifiedFont(ReaderFontSelection.File("path-1"))
        assertEquals(ReaderFontSelection.File("path-1"), unified.bodyFont)
        assertEquals(ReaderFontSelection.FollowBody, unified.titleFont)
        assertEquals(ReaderFontSelection.FollowBody, unified.headerFont)
    }

    @Test
    fun `系统预设选择同样统一三处`() {
        val unified = ReaderTextStyle(bodyFont = ReaderFontSelection.File("path-1"))
            .withUnifiedFont(ReaderFontSelection.Mono)
        assertEquals(ReaderFontSelection.Mono, unified.bodyFont)
        assertEquals(ReaderFontSelection.FollowBody, unified.titleFont)
        assertEquals(ReaderFontSelection.FollowBody, unified.headerFont)
    }

    @Test
    fun `其余排版字段保持不变`() {
        val style = ReaderTextStyle(textSize = 24, lineSpacing = 18, titleSize = 24)
        val unified = style.withUnifiedFont(ReaderFontSelection.Serif)
        assertEquals(24, unified.textSize)
        assertEquals(18, unified.lineSpacing)
        assertEquals(24, unified.titleSize)
    }
}
