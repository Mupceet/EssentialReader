package io.legado.app.feature.reader.core.layout

import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderTextStyle
import io.legado.app.feature.reader.core.model.ReaderUnderline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageUnderlineTest {
    private val style = ReaderTextStyle(0xff111111.toInt(), 10f)

    private fun paragraph(text: String, indent: Int = 0) = ReaderMeasuredBlock.InlineParagraph(
        items = text.mapIndexed { index, char ->
            ReaderMeasuredInlineItem.Text(char.toString(), 10f, style, index)
        },
        indentCharacters = indent,
        alignment = ReaderTextAlignment.START,
        lineHeightPx = 10f,
        baselineOffsetPx = 8f,
        baseTextSizePx = 10f,
    )

    @Test fun pageUnderlineUsesRenderedLineBoundsAndConfiguredDashPattern() {
        val page = ReaderPaginator.paginateBlocks(
            listOf(paragraph("甲乙", indent = 1)),
            ReaderPaginationConfig(
                0, "", 40, 40, 0f, 0f, 0f, 0f, 10f, 8f,
                pageUnderline = ReaderPageUnderline(
                    colorArgb = 0xff123456.toInt(),
                    widthPx = 2f,
                    offsetPx = 3f,
                    extendToColumn = false,
                    dashed = true,
                    dashOnPx = 7f,
                    dashOffPx = 4f,
                ),
            ),
        ).single()
        val underline = page.elements.filterIsInstance<ReaderElement.Rule>().single()

        assertEquals(10f, underline.bounds.left)
        assertEquals(30f, underline.bounds.right)
        assertEquals(13f, underline.bounds.top)
        assertEquals(0xff123456.toInt(), underline.colorArgb)
        assertEquals(2f, underline.widthPx)
        assertTrue(underline.dashed)
        assertEquals(7f, underline.dashOnPx)
        assertEquals(4f, underline.dashOffPx)
        assertTrue(underline.overlayStyledUnderline)
    }

    @Test
    fun nonExtendedUnderlineSkipsRetainedIndentGlyphs() {
        val items = listOf(
            ReaderMeasuredInlineItem.Text("　", 10f, style, 0),
            ReaderMeasuredInlineItem.Text("　", 10f, style, 1),
            ReaderMeasuredInlineItem.Text("甲", 10f, style, 2),
            ReaderMeasuredInlineItem.Text("乙", 10f, style, 3),
        )
        val page = ReaderPaginator.paginateBlocks(
            listOf(
                ReaderMeasuredBlock.InlineParagraph(
                    items = items,
                    indentCharacters = 0,
                    alignment = ReaderTextAlignment.START,
                    lineHeightPx = 10f,
                    baselineOffsetPx = 8f,
                    baseTextSizePx = 10f,
                    leadingIndentItems = 2,
                ),
            ),
            ReaderPaginationConfig(
                0, "", 40, 40, 0f, 0f, 0f, 0f, 10f, 8f,
                pageUnderline = ReaderPageUnderline(
                    0xff123456.toInt(), 1f, 0f, false, false,
                ),
            ),
        ).single()

        val underline = page.elements.filterIsInstance<ReaderElement.Rule>().single()
        assertEquals(20f, underline.bounds.left)
        assertEquals(40f, underline.bounds.right)
    }

    @Test fun contentRuleKeepsNormalContentLayer() {
        val page = ReaderPaginator.paginateBlocks(
            listOf(
                ReaderMeasuredBlock.Rule(
                    colorArgb = 0xff000000.toInt(),
                    widthPx = 2f,
                    dashed = false,
                    verticalPaddingPx = 3f,
                ),
            ),
            ReaderPaginationConfig(0, "", 40, 40, 0f, 0f, 0f, 0f, 10f, 8f),
        ).single()

        assertFalse(page.elements.filterIsInstance<ReaderElement.Rule>().single().overlayStyledUnderline)
    }

    @Test fun extendedUnderlineUsesOnlyItsCurrentDoublePageColumn() {
        val page = ReaderPaginator.paginateBlocks(
            listOf(paragraph("甲乙丙丁戊己")),
            ReaderPaginationConfig(
                0, "", 40, 35, 5f, 0f, 5f, 5f, 10f, 8f,
                columnCount = 2,
                pageUnderline = ReaderPageUnderline(0xff000000.toInt(), 1f, 0f, true, false),
            ),
        ).single()
        val underlines = page.elements.filterIsInstance<ReaderElement.Rule>()

        assertEquals(listOf(5f, 5f, 5f, 25f, 25f, 25f), underlines.map { it.bounds.left })
        assertEquals(listOf(15f, 15f, 15f, 35f, 35f, 35f), underlines.map { it.bounds.right })
    }

    @Test fun bottomJustificationMovesUnderlineWithItsTextRow() {
        val page = ReaderPaginator.paginateBlocks(
            listOf(paragraph("甲乙丙")),
            ReaderPaginationConfig(
                0, "", 10, 45, 0f, 0f, 0f, 5f, 10f, 8f,
                textBottomJustify = true,
                pageUnderline = ReaderPageUnderline(0xff000000.toInt(), 1f, 0f, false, false),
            ),
        ).single()

        assertEquals(
            listOf(10f, 25f, 40f),
            page.elements.filterIsInstance<ReaderElement.Rule>().map { it.bounds.top },
        )
    }

    // 高亮规则与用户笔记的行内下划线都经 style.underline 表达：正文线让位（正文 < 高亮/笔记）
    private fun styledItem(char: Char, position: Int, styleOverride: ReaderTextStyle) =
        ReaderMeasuredInlineItem.Text(char.toString(), 10f, styleOverride, position)

    private fun paragraphWith(vararg items: ReaderMeasuredInlineItem.Text) =
        ReaderMeasuredBlock.InlineParagraph(
            items = items.toList(),
            indentCharacters = 0,
            alignment = ReaderTextAlignment.START,
            lineHeightPx = 10f,
            baselineOffsetPx = 8f,
            baseTextSizePx = 10f,
        )

    private val styledUnderlineStyle = style.copy(
        underline = ReaderUnderline(mode = 1, colorArgb = 0xff880000.toInt(), widthPx = 1f, offsetPx = 2f),
    )

    @Test fun bodyUnderlineYieldsToStyledUnderlineRanges() {
        // 甲乙[丙带规则下划线]丁 → 正文线断开成 甲乙、丁 两段，丙下只有规则线
        val page = ReaderPaginator.paginateBlocks(
            listOf(
                paragraphWith(
                    styledItem('甲', 0, style),
                    styledItem('乙', 1, style),
                    styledItem('丙', 2, styledUnderlineStyle),
                    styledItem('丁', 3, style),
                ),
            ),
            ReaderPaginationConfig(
                0, "", 60, 40, 0f, 0f, 0f, 0f, 10f, 8f,
                pageUnderline = ReaderPageUnderline(0xff123456.toInt(), 1f, 0f, false, false),
            ),
        ).single()
        val underlines = page.elements.filterIsInstance<ReaderElement.Rule>()

        assertEquals(listOf(0f to 20f, 30f to 40f), underlines.map { it.bounds.left to it.bounds.right })
    }

    @Test fun fullyCoveredRowEmitsNoBodyUnderline() {
        val page = ReaderPaginator.paginateBlocks(
            listOf(
                paragraphWith(
                    styledItem('甲', 0, styledUnderlineStyle),
                    styledItem('乙', 1, styledUnderlineStyle),
                ),
            ),
            ReaderPaginationConfig(
                0, "", 40, 40, 0f, 0f, 0f, 0f, 10f, 8f,
                pageUnderline = ReaderPageUnderline(0xff123456.toInt(), 1f, 0f, false, false),
            ),
        ).single()

        assertTrue(page.elements.filterIsInstance<ReaderElement.Rule>().isEmpty())
    }

    @Test fun backgroundOnlyStyleKeepsBodyUnderline() {
        // 纯荧光带（背景色无线）不压正文线：带与线不同形态，同屏只有一条线
        val bandedStyle = style.copy(backgroundArgb = 0x80ffcc00.toInt())
        val page = ReaderPaginator.paginateBlocks(
            listOf(
                paragraphWith(
                    styledItem('甲', 0, style),
                    styledItem('乙', 1, bandedStyle),
                    styledItem('丙', 2, style),
                ),
            ),
            ReaderPaginationConfig(
                0, "", 40, 40, 0f, 0f, 0f, 0f, 10f, 8f,
                pageUnderline = ReaderPageUnderline(0xff123456.toInt(), 1f, 0f, false, false),
            ),
        ).single()
        val underlines = page.elements.filterIsInstance<ReaderElement.Rule>()

        assertEquals(listOf(0f to 30f), underlines.map { it.bounds.left to it.bounds.right })
    }

    @Test fun extendedUnderlineStopsAtCoveredGaps() {
        val config = ReaderPaginationConfig(
            0, "", 70, 40, 5f, 0f, 5f, 5f, 10f, 8f,
            pageUnderline = ReaderPageUnderline(0xff000000.toInt(), 1f, 0f, true, false),
        )
        fun pageOf(vararg items: ReaderMeasuredInlineItem.Text) =
            ReaderPaginator.paginateBlocks(listOf(paragraphWith(*items)), config).single()

        // 行首被覆盖：首段不向左延伸（越过缺口会画到规则线下面）
        val headCovered = pageOf(
            styledItem('甲', 0, styledUnderlineStyle),
            styledItem('乙', 1, style),
            styledItem('丙', 2, style),
            styledItem('丁', 3, style),
            styledItem('戊', 4, style),
        ).elements.filterIsInstance<ReaderElement.Rule>()
        assertEquals(listOf(15f to 65f), headCovered.map { it.bounds.left to it.bounds.right })

        // 行中被覆盖：两段各贴自己那端延伸，缺口保持缺口
        val middleCovered = pageOf(
            styledItem('甲', 0, style),
            styledItem('乙', 1, style),
            styledItem('丙', 2, styledUnderlineStyle),
            styledItem('丁', 3, style),
            styledItem('戊', 4, style),
        ).elements.filterIsInstance<ReaderElement.Rule>()
        assertEquals(
            listOf(5f to 25f, 35f to 65f),
            middleCovered.map { it.bounds.left to it.bounds.right },
        )

        // 行末被覆盖：末段不向右延伸
        val tailCovered = pageOf(
            styledItem('甲', 0, style),
            styledItem('乙', 1, style),
            styledItem('丙', 2, style),
            styledItem('丁', 3, style),
            styledItem('戊', 4, styledUnderlineStyle),
        ).elements.filterIsInstance<ReaderElement.Rule>()
        assertEquals(listOf(5f to 45f), tailCovered.map { it.bounds.left to it.bounds.right })
    }
}
