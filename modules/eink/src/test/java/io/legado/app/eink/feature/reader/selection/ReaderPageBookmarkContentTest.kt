package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.eink.contract.ReaderPageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/** 页面书签显示载荷拼装（契约 v2：书签显示语义归模块）。 */
class ReaderPageBookmarkContentTest {

    @Test
    fun `章节名取快照标题`() {
        val page = snapshot(title = "第十二章 夜航", line("正文"))
        assertEquals("第十二章 夜航", page.toPageBookmarkContent().chapterName)
    }

    @Test
    fun `页文本按行拼接且行间换行`() {
        val page = snapshot(
            title = "章",
            line("标题行", positions = intArrayOf(0), isTitle = true),
            line("第一段", positions = intArrayOf(0)),
            line("第二段", positions = intArrayOf(3)),
        )
        assertEquals("标题行\n第一段\n第二段", page.toPageBookmarkContent().pageText)
    }

    @Test
    fun `行内多段拼接为连续文本`() {
        val page = snapshot(
            title = "章",
            line("前段", "后段", positions = intArrayOf(0, 2)),
        )
        assertEquals("前段后段", page.toPageBookmarkContent().pageText)
    }

    @Test
    fun `无正文行的页文本为空串`() {
        val page = snapshot(title = "章")
        assertEquals("", page.toPageBookmarkContent().pageText)
    }

    private fun line(
        vararg chunks: String,
        positions: IntArray = intArrayOf(0),
        isTitle: Boolean = false,
    ): ReaderPageLine = ReaderPageLine(
        baseY = 60f,
        isTitle = isTitle,
        chunks = chunks.toList(),
        x = FloatArray(chunks.size) { it * 100f },
        chapterPositions = positions,
        top = 30f,
        bottom = 70f,
    )

    private fun snapshot(title: String, vararg lines: ReaderPageLine) = ReaderPageSnapshot(
        title = title,
        readProgress = "1/1",
        titleSpec = ReaderPaintSpec(20f, 0f, null, null),
        contentSpec = ReaderPaintSpec(20f, 0f, null, null),
        lines = lines.toList(),
        images = emptyList(),
    )
}
