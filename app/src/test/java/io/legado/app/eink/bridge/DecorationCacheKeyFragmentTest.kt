package io.legado.app.eink.bridge

import io.legado.app.help.config.ReadBookConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DecorationCacheKeyFragmentTest {

    private val base = ReadBookConfig.Config()

    @Test
    fun `页眉字号变化改变键片段`() {
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(headerFontSize = 14)),
        )
    }

    @Test
    fun `页眉上下边距变化改变键片段`() {
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(headerPaddingTop = 4)),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(footerPaddingBottom = 8)),
        )
    }

    @Test
    fun `分割线与显隐变化改变键片段`() {
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(showHeaderLine = true)),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(headerMode = 1)),
        )
    }

    @Test
    fun `左右边距同样进键但值独立`() {
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(headerPaddingLeft = 20)),
        )
    }

    @Test
    fun `页脚侧字段全部进键`() {
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(footerFontSize = 14)),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(footerFont = "serif")),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(footerMode = 1)),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(showFooterLine = false)),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(applyHeaderStyle = false)),
        )
    }

    @Test
    fun `其余装饰字段也全部进键`() {
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(headerFont = "serif")),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(headerPaddingBottom = 4)),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(headerPaddingRight = 20)),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(footerPaddingTop = 2)),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(footerPaddingLeft = 20)),
        )
    }

    @Test
    fun `同值键片段稳定`() {
        assertEquals(decorationCacheKeyFragment(base), decorationCacheKeyFragment(base.copy()))
    }
}
