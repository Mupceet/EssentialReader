package io.legado.app.help.bookmark

import io.legado.app.data.entities.BookMarking
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkingExporterTest {

    private fun marking(
        chapterIndex: Int,
        chapterName: String,
        selectedText: String,
        note: String = "",
        createdAt: Long = 0,
    ) = BookMarking(
        id = "id-$chapterIndex-$createdAt",
        bookUrl = "https://a",
        bookName = "书",
        bookAuthor = "作者",
        chapterIndex = chapterIndex,
        anchorJson = GSON.toJson(
            io.legado.app.domain.model.TextProcessAnchor(
                chapterIndex = chapterIndex,
                selectedText = selectedText,
                normalizedTextHash = "h",
            )
        ),
        styleJson = null,
        note = note,
        chapterName = chapterName,
        createdAt = createdAt,
    )

    @Test
    fun `按章分组，划线摘录与想法行`() {
        val md = MarkingExporter.formatToMarkdown(
            "书", "作者",
            listOf(
                marking(1, "第二章", "第二句", createdAt = 2),
                marking(1, "第二章", "第一句", note = "有感", createdAt = 1),
                marking(0, "第一章", "开头"),
            )
        )
        assertEquals(
            """
            # 书

            作者：作者

            ## 第一章

            > 开头

            ## 第二章

            > 第一句

            想法：有感

            > 第二句
            """.trimIndent() + "\n",
            md
        )
    }

    @Test
    fun `空章节名回退序号章题，anchorJson 损坏条目跳过`() {
        val bad = marking(3, "", "x").copy(anchorJson = "{bad")
        val md = MarkingExporter.formatToMarkdown("书", "", listOf(marking(2, "", "文本"), bad))
        assertEquals("# 书\n\n## 第 3 章\n\n> 文本\n", md)
    }
}
