package io.legado.app.eink.bridge

import io.legado.app.data.entities.BookGroup
import io.legado.app.eink.contract.BookshelfGroupUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BookshelfGroupEngineImpl 的纯函数面：分组快照组装（hideEmpty 过滤 +
 * 名称解析注入）。数据库交互不在单测范围（宿主层既有查询，真机验证）。
 */
class BookshelfGroupEngineImplTest {

    private fun group(id: Long, name: String = "组$id", order: Int = 0) =
        BookGroup(groupId = id, groupName = name, order = order)

    private val nameOf: (BookGroup) -> String = { it.groupName }

    // ---- buildGroupUiModels ----

    @Test
    fun `hideEmpty 关闭时全部组可见`() {
        val groups = listOf(
            group(BookGroup.IdAll), group(-100L), group(0b1, order = 1), group(0b10, order = 2)
        )
        val result = buildGroupUiModels(
            groups, systemCounts = mapOf(-100L to 0), userCounts = emptyMap(),
            hideEmpty = false, nameOf = nameOf,
        )
        assertEquals(4, result.size)
        assertEquals(BookshelfGroupUiModel(0b1, "组1", 0), result[2])
    }

    @Test
    fun `hideEmpty 开启时空组被过滤但全部保留`() {
        val groups = listOf(
            group(BookGroup.IdAll), group(-100L), group(0b1, order = 1), group(0b10, order = 2)
        )
        val result = buildGroupUiModels(
            groups,
            systemCounts = mapOf(BookGroup.IdAll to 5, -100L to 3),
            userCounts = mapOf(0b1L to 0, 0b10L to 7),
            hideEmpty = true,
            nameOf = nameOf,
        )
        // 全部(-1) 保留（计数 5）；未分组(-100) 计数 3 保留；组1 空被滤；组2 保留
        assertEquals(listOf(-1L, -100L, 0b10L), result.map { it.groupId })
    }

    @Test
    fun `名称解析经注入的 nameOf`() {
        val result = buildGroupUiModels(
            listOf(group(-1L)), systemCounts = mapOf(-1L to 2), userCounts = emptyMap(),
            hideEmpty = false, nameOf = { "解析名" },
        )
        assertEquals("解析名", result.single().name)
    }
}
