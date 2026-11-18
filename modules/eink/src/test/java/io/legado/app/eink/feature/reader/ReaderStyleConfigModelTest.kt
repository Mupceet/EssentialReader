package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.FallbackReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleConfigModelTest {

    @Test
    fun `回落目录下标题组隐藏其余三组可见`() {
        // Fallback 目录 = 正文五标量 + 正文/页眉/页脚三组四边距（页眉/页脚边距行
        // available=true），无标题行：按 spec §4.3「组内行全不可用才整组隐藏」，
        // 仅标题组与其锚点隐藏，页眉/页脚组经各自边距行可见（现状口径——
        // 旧宿主下边距弹框三子 Tab 页眉/页脚照常呈现）
        val catalog = FallbackReaderStyleCatalog.create()
        assertTrue(regionGroupVisible(catalog, StyleRegionGroup.BODY))
        assertFalse(regionGroupVisible(catalog, StyleRegionGroup.TITLE))
        assertTrue(regionGroupVisible(catalog, StyleRegionGroup.HEADER))
        assertTrue(regionGroupVisible(catalog, StyleRegionGroup.FOOTER))
    }

    @Test
    fun `全量目录下四组均可见`() {
        val catalog = ReaderStyleCatalog(
            listOf(
                ReaderStyleParam.Stepped(Ids.BODY_SIZE, true, true, 5f, 50f, 20f),
                ReaderStyleParam.Stepped(Ids.BODY_LETTER_SPACING, true, true, -0.5f, 0.5f, 0.1f),
                ReaderStyleParam.Stepped(Ids.BODY_INDENT, true, true, 0f, 4f, 2f),
                ReaderStyleParam.Stepped(Ids.BODY_LINE_SPACING, true, true, 0f, 20f, 12f),
                ReaderStyleParam.Stepped(Ids.BODY_PARAGRAPH_SPACING, true, true, 0f, 20f, 2f),
                ReaderStyleParam.Choice(Ids.TITLE_MODE, true, true, emptyList(), 0),
                ReaderStyleParam.Stepped(Ids.TITLE_SIZE, true, true, 8f, 60f, 20f),
                ReaderStyleParam.Choice(Ids.HEADER_MODE, true, true, emptyList(), 0),
                ReaderStyleParam.Stepped(Ids.HEADER_SIZE, true, true, 0f, 100f, 12f),
                ReaderStyleParam.Toggle(Ids.FOOTER_VISIBILITY, true, true, true),
                ReaderStyleParam.Stepped(Ids.FOOTER_SIZE, true, true, 0f, 100f, 12f),
                ReaderStyleParam.Stepped(Ids.BODY_PADDING_TOP, true, true, 0f, 200f, 6f),
                ReaderStyleParam.Stepped(Ids.HEADER_PADDING_TOP, true, true, 0f, 300f, 0f),
                ReaderStyleParam.Stepped(Ids.FOOTER_PADDING_TOP, true, true, 0f, 300f, 6f),
            )
        )
        assertTrue(StyleRegionGroup.entries.all { regionGroupVisible(catalog, it) })
    }

    @Test
    fun `目录缺正文全部行则正文组隐藏`() {
        val catalog = ReaderStyleCatalog(emptyList())
        assertFalse(StyleRegionGroup.entries.any { regionGroupVisible(catalog, it) })
    }

    @Test
    fun `分组标签与轴枚举稳定`() {
        assertEquals(listOf("正文", "标题", "页眉", "页脚"), StyleRegionGroup.entries.map { it.displayLabel })
        assertEquals(listOf("字体", "信息", "边距"), StyleFunctionTab.entries.map { it.displayLabel })
    }
}
