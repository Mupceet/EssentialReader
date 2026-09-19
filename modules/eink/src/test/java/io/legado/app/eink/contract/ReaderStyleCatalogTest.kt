package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleCatalogTest {

    private val catalog = FallbackReaderStyleCatalog.create()

    @Test
    fun `回落目录仅含历代17个参数且id唯一`() {
        assertEquals(17, catalog.params.size)
        assertEquals(17, catalog.params.map { it.id }.toSet().size)
    }

    @Test
    fun `回落目录不含协商扩展参数`() {
        val ids = catalog.params.map { it.id }
        assertFalse(ids.contains(ReaderStyleParamIds.BODY_FONT))
        assertFalse(ids.contains(ReaderStyleParamIds.TITLE_SIZE))
        assertFalse(ids.contains(ReaderStyleParamIds.HEADER_SIZE))
        assertFalse(ids.contains(ReaderStyleParamIds.FOOTER_DIVIDER))
    }

    @Test
    fun `回落目录值域沿用模块既有常量`() {
        fun stepped(id: String) = catalog.find(id) as ReaderStyleParam.Stepped
        assertEquals(8f..40f, stepped(ReaderStyleParamIds.BODY_SIZE).let { it.min..it.max })
        assertEquals(0f..0.5f, stepped(ReaderStyleParamIds.BODY_LETTER_SPACING).let { it.min..it.max })
        assertEquals(0..4, stepped(ReaderStyleParamIds.BODY_INDENT).let { it.min.toInt()..it.max.toInt() })
        assertEquals(0..30, stepped(ReaderStyleParamIds.BODY_LINE_SPACING).let { it.min.toInt()..it.max.toInt() })
        assertEquals(0..10, stepped(ReaderStyleParamIds.BODY_PARAGRAPH_SPACING).let { it.min.toInt()..it.max.toInt() })
        assertEquals(20f, stepped(ReaderStyleParamIds.BODY_SIZE).default)
    }

    @Test
    fun `页眉页脚左右边距不影响分页其余全部影响`() {
        val paintOnly = setOf(
            ReaderStyleParamIds.HEADER_PADDING_LEFT,
            ReaderStyleParamIds.HEADER_PADDING_RIGHT,
            ReaderStyleParamIds.FOOTER_PADDING_LEFT,
            ReaderStyleParamIds.FOOTER_PADDING_RIGHT,
        )
        catalog.params.forEach { param ->
            if (param.id in paintOnly) {
                assertFalse("${param.id} 应为纯绘制（不影响分页）", param.affectsLayout)
            } else {
                assertTrue("${param.id} 应影响分页", param.affectsLayout)
            }
        }
    }

    @Test
    fun `全部Stepped参数默认值落在值域内`() {
        val stepped = catalog.params.filterIsInstance<ReaderStyleParam.Stepped>()
        assertEquals(catalog.params.size, stepped.size)
        stepped.forEach { param ->
            assertTrue(
                "${param.id} default=${param.default} 不在 ${param.min}..${param.max} 内",
                param.default >= param.min && param.default <= param.max,
            )
        }
    }

    @Test
    fun `find 按 id 查找`() {
        assertTrue(catalog.find(ReaderStyleParamIds.BODY_SIZE) is ReaderStyleParam.Stepped)
        assertEquals(null, catalog.find("no.such.id"))
    }
}
