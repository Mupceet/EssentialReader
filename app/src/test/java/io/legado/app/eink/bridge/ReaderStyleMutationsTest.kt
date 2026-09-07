package io.legado.app.eink.bridge

import io.legado.app.domain.gateway.ReadStyleIntKey
import io.legado.app.domain.gateway.ReadStyleMutation
import io.legado.app.domain.gateway.ReadStyleStringKey
import io.legado.app.eink.contract.ReaderFontSelection as FontSel
import io.legado.app.eink.contract.ReaderTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleMutationsTest {

    private fun ints(mutations: List<ReadStyleMutation>) =
        mutations.filterIsInstance<ReadStyleMutation.IntValue>()

    private fun strings(mutations: List<ReadStyleMutation>) =
        mutations.filterIsInstance<ReadStyleMutation.StringValue>()

    @Test
    fun `扩展字段全null时只写17个基础键且不写TitleSize`() {
        val mutations = buildStyleMutations(ReaderTextStyle(), currentBodyFontPath = "/f.ttf")
        assertEquals(17, mutations.size)
        assertTrue(strings(mutations).none { it.key == ReadStyleStringKey.TitleFont })
        assertTrue(ints(mutations).none { it.key == ReadStyleIntKey.TitleSize })
        assertTrue(ints(mutations).none { it.key == ReadStyleIntKey.TextBold })
    }

    @Test
    fun `titleSize设置后写TitleSize不再钉平`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(textSize = 24, titleSize = 30),
            currentBodyFontPath = "",
        )
        assertEquals(24, ints(mutations).first { it.key == ReadStyleIntKey.TextSize }.value)
        assertEquals(30, ints(mutations).first { it.key == ReadStyleIntKey.TitleSize }.value)
    }

    @Test
    fun `FollowBody展开为正文文件路径三键同写`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(
                bodyFont = FontSel.File("/fonts/x.ttf"),
                titleFont = FontSel.FollowBody,
                headerFont = FontSel.FollowBody,
            ),
            currentBodyFontPath = "/old.ttf",
        )
        assertEquals("/fonts/x.ttf", strings(mutations).first { it.key == ReadStyleStringKey.TextFont }.value)
        assertEquals("/fonts/x.ttf", strings(mutations).first { it.key == ReadStyleStringKey.TitleFont }.value)
        assertEquals("/fonts/x.ttf", strings(mutations).first { it.key == ReadStyleStringKey.HeaderFont }.value)
    }

    @Test
    fun `正文为系统预设时跟随者写空串`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(
                bodyFont = FontSel.Serif,
                titleFont = FontSel.FollowBody,
                headerFont = FontSel.FollowBody,
            ),
            currentBodyFontPath = "/old.ttf",
        )
        assertEquals("", strings(mutations).first { it.key == ReadStyleStringKey.TextFont }.value)
        assertEquals("", strings(mutations).first { it.key == ReadStyleStringKey.TitleFont }.value)
        assertEquals("", strings(mutations).first { it.key == ReadStyleStringKey.HeaderFont }.value)
    }

    @Test
    fun `bodyFont为null时FollowBody按宿主当前正文路径展开`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(titleFont = FontSel.FollowBody),
            currentBodyFontPath = "/host.ttf",
        )
        assertEquals("/host.ttf", strings(mutations).first { it.key == ReadStyleStringKey.TitleFont }.value)
    }

    @Test
    fun `显隐开关映射宿主模式值`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(headerVisible = false, footerVisible = false),
            currentBodyFontPath = "",
        )
        assertEquals(2, ints(mutations).first { it.key == ReadStyleIntKey.HeaderMode }.value)
        assertEquals(1, ints(mutations).first { it.key == ReadStyleIntKey.FooterMode }.value)
    }

    @Test
    fun `显隐开关显示方向映射宿主模式值`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(headerVisible = true, footerVisible = true),
            currentBodyFontPath = "",
        )
        assertEquals(1, ints(mutations).first { it.key == ReadStyleIntKey.HeaderMode }.value)
        assertEquals(0, ints(mutations).first { it.key == ReadStyleIntKey.FooterMode }.value)
    }

    @Test
    fun `titleMode越界钳制到0到2`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(titleMode = 5),
            currentBodyFontPath = "",
        )
        assertEquals(2, ints(mutations).first { it.key == ReadStyleIntKey.TitleMode }.value)
    }

    @Test
    fun `字重写入钳制到100到900`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(bodyWeight = 50, titleWeight = 950),
            currentBodyFontPath = "",
        )
        assertEquals(100, ints(mutations).first { it.key == ReadStyleIntKey.TextBold }.value)
        assertEquals(900, ints(mutations).first { it.key == ReadStyleIntKey.TitleBold }.value)
    }

    @Test
    fun `宿主遗留字重值归一化`() {
        assertEquals(900, normalizeHostWeight(1))
        assertEquals(300, normalizeHostWeight(2))
        assertEquals(500, normalizeHostWeight(500))
        assertEquals(400, normalizeHostWeight(0))
    }

    @Test
    fun `缩进展开为全角空格`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(indentChars = 3),
            currentBodyFontPath = "",
        )
        assertEquals("　　　", strings(mutations).first { it.key == ReadStyleStringKey.ParagraphIndent }.value)
    }
}
