package io.legado.app.eink.bridge

import io.legado.app.eink.contract.MarkingUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MarksEngineImpl.kt 顶层纯函数行为锚定（thought 推导 / 确认文案）。 */
class MarksEngineImplTest {

    @Test
    fun `underlineMode 2 为想法，实线与空样式为划线`() {
        assertTrue(markingThought("""{"underlineMode":2}"""))
        assertFalse(markingThought("""{"underlineMode":1}"""))
        assertFalse(markingThought(null))
        assertFalse(markingThought("{bad"))
    }

    @Test
    fun `确认文案含章节名`() {
        val msg = confirmJumpMessage("第二章 坠落")
        assertTrue(msg.contains("第二章 坠落"))
    }

    private fun marking(
        id: String,
        chapter: Int,
        pos: Int,
        createdAt: Long,
    ) = MarkingUiModel(
        id = id, chapterIndex = chapter, chapterName = "第${chapter + 1}章",
        chapterPos = pos, selectedText = id, note = "", thought = false,
        createdAt = createdAt,
    )

    @Test
    fun `笔记按章内正文本位置升序而非创建时间`() {
        // 后补的划线（创建时间最晚）但位置在章首 → 应排最前
        val ordered = orderMarkingsByPosition(
            listOf(
                marking("晚补早位", chapter = 0, pos = 10, createdAt = 900),
                marking("先选后位", chapter = 0, pos = 500, createdAt = 100),
                marking("次章", chapter = 1, pos = 5, createdAt = 50),
            ),
        )
        assertEquals(listOf("晚补早位", "先选后位", "次章"), ordered.map { it.id })
    }

    @Test
    fun `同位置按创建时间兜底且排序稳定`() {
        val ordered = orderMarkingsByPosition(
            listOf(
                marking("同位置晚", chapter = 0, pos = 42, createdAt = 200),
                marking("同位置早", chapter = 0, pos = 42, createdAt = 100),
            ),
        )
        assertEquals(listOf("同位置早", "同位置晚"), ordered.map { it.id })
    }
}
