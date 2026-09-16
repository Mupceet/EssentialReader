package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfGroupUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

/** 排序模式 ▲▼ 的面板乐观重排（真值收敛由宿主流负责）。 */
class ShelfSelectorMoveTest {

    private fun group(id: Long) = BookshelfGroupUiModel(id, "组$id", 1)

    @Test
    fun `上移与相邻行交换`() {
        val list = listOf(group(1L), group(2L), group(3L))
        assertEquals(
            listOf(2L, 1L, 3L),
            moveGroupInList(list, 2L, up = true).map { it.groupId },
        )
    }

    @Test
    fun `下移与相邻行交换`() {
        val list = listOf(group(1L), group(2L), group(3L))
        assertEquals(
            listOf(1L, 3L, 2L),
            moveGroupInList(list, 2L, up = false).map { it.groupId },
        )
    }

    @Test
    fun `边界与未知 id 原样返回`() {
        val list = listOf(group(1L), group(2L))
        assertEquals(list, moveGroupInList(list, 1L, up = true))
        assertEquals(list, moveGroupInList(list, 2L, up = false))
        assertEquals(list, moveGroupInList(list, 99L, up = true))
    }
}
