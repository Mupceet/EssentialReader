package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器 UiState 派生展示逻辑与字重域钳制。
 */
class ReaderUiStateTest {

    @Test
    fun `页码指示无页数时为空`() {
        assertEquals("", ReaderUiState(pageCount = 0).pageIndicator)
        assertEquals("1/1", ReaderUiState(pageCount = 1, pageIndex = 0).pageIndicator)
        assertEquals("3/15", ReaderUiState(pageCount = 15, pageIndex = 2).pageIndicator)
    }

    @Test
    fun `页数与进度用双空格拼接`() {
        assertEquals("", ReaderUiState().pageAndTotal)
        assertEquals("3/15", ReaderUiState(pageCount = 15, pageIndex = 2).pageAndTotal)
        assertEquals(
            "3/15  12.3%",
            ReaderUiState(pageCount = 15, pageIndex = 2, readProgress = "12.3%").pageAndTotal,
        )
        assertEquals(
            "只有进度时直接展示",
            "45.0%",
            ReaderUiState(pageCount = 0, readProgress = "45.0%").pageAndTotal,
        )
    }

    @Test
    fun `字重域零到二与一百到九百透传`() {
        assertEquals(0, coerceWeight(0))
        assertEquals(1, coerceWeight(1))
        assertEquals(2, coerceWeight(2))
        assertEquals(100, coerceWeight(100))
        assertEquals(450, coerceWeight(450))
        assertEquals(900, coerceWeight(900))
    }

    @Test
    fun `字重域外值钳入自定义区间`() {
        assertEquals(100, coerceWeight(3))
        assertEquals(100, coerceWeight(99))
        assertEquals(900, coerceWeight(901))
    }

    @Test
    fun `自动翻页间隔常量与宿主 autoReadSpeed 对齐`() {
        assertEquals(10, DEFAULT_AUTO_INTERVAL_SEC)
        assertTrue(MIN_AUTO_INTERVAL_SEC >= 1)
        assertTrue(MAX_AUTO_INTERVAL_SEC >= DEFAULT_AUTO_INTERVAL_SEC)
    }
}
