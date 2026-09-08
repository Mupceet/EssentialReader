package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.FallbackReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录 → UI 呈现辅助：可用性、值域、默认档与浮点步进映射。回落目录
 * 缺失的协商参数按「不钳制 / 0 兜底 / 不可用」处理（VM 侧依赖该语义
 * 决定是否透传与重排）。
 */
class ReaderStyleCatalogUiTest {

    private val catalog = FallbackReaderStyleCatalog.create()

    @Test
    fun `可用性按目录登记判定`() {
        assertTrue(catalog.available(Ids.BODY_SIZE))
        assertFalse("协商扩展参数不在回落目录", catalog.available(Ids.BODY_FONT))
    }

    @Test
    fun `整型值域取目录边界`() {
        assertEquals(8..40, catalog.intRange(Ids.BODY_SIZE))
        assertEquals(0..4, catalog.intRange(Ids.BODY_INDENT))
        assertEquals("缺失参数兜底 0..0", 0..0, catalog.intRange(Ids.BODY_FONT))
    }

    @Test
    fun `整型钳制越界值且缺失参数透传`() {
        assertEquals(8, catalog.clampInt(Ids.BODY_SIZE, 1))
        assertEquals(20, catalog.clampInt(Ids.BODY_SIZE, 20))
        assertEquals(40, catalog.clampInt(Ids.BODY_SIZE, 99))
        assertEquals("缺失参数原值透传", 5, catalog.clampInt(Ids.BODY_FONT, 5))
    }

    @Test
    fun `整型默认值取目录默认`() {
        assertEquals(20, catalog.defaultInt(Ids.BODY_SIZE))
        assertEquals(2, catalog.defaultInt(Ids.BODY_INDENT))
        assertEquals("缺失参数兜底 0", 0, catalog.defaultInt(Ids.BODY_FONT))
    }

    @Test
    fun `浮点步进档位域按步进取整映射`() {
        // 回落目录字距值域 0..0.5，步进 0.05 → 档位 0..10
        assertEquals(0..10, catalog.floatStepIndexRange(Ids.BODY_LETTER_SPACING, LETTER_SPACING_STEP))
        assertEquals("缺失参数兜底 0..0", 0..0, catalog.floatStepIndexRange(Ids.BODY_FONT, LETTER_SPACING_STEP))
    }
}
