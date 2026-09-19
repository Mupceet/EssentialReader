package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 书架分组状态宿主：选中覆盖优先/追平清除（镜像 BookshelfStyleState
 * 语义）+ 选中组被隐藏后回退「全部」。收集经 backgroundScope + runCurrent
 * 推进虚拟时钟。
 */
class BookshelfGroupStateTest {

    private fun group(id: Long, count: Int = 1) =
        BookshelfGroupUiModel(groupId = id, name = "组$id", bookCount = count)

    @Test
    fun `提交后覆盖优先于快照`() = runTest {
        val saved = MutableStateFlow(BookshelfGroupIds.ALL)
        val state = BookshelfGroupState(saved, MutableStateFlow(listOf(group(1L))), backgroundScope)
        val values = mutableListOf<Long>()
        backgroundScope.launch { state.selected.toList(values) }

        state.submit(1L)
        runCurrent()
        assertEquals(1L, values.last())
    }

    @Test
    fun `快照追平后清除覆盖且后续外部变化生效`() = runTest {
        val saved = MutableStateFlow(BookshelfGroupIds.ALL)
        val state = BookshelfGroupState(saved, MutableStateFlow(listOf(group(1L))), backgroundScope)
        val values = mutableListOf<Long>()
        backgroundScope.launch { state.selected.toList(values) }

        state.submit(1L)
        runCurrent()
        saved.value = 1L // 落库追平，覆盖清除
        runCurrent()
        saved.value = BookshelfGroupIds.ALL // 外部（完整模式）再改
        runCurrent()
        assertEquals(BookshelfGroupIds.ALL, values.last())
    }

    @Test
    fun `选中组被隐藏后回退全部`() = runTest {
        val saved = MutableStateFlow(1L)
        val groups = MutableStateFlow(listOf(group(1L)))
        val state = BookshelfGroupState(saved, groups, backgroundScope)
        val values = mutableListOf<Long>()
        backgroundScope.launch { state.selected.toList(values) }

        runCurrent()
        assertEquals(1L, values.last())

        groups.value = emptyList() // 组 1 被删/隐藏
        runCurrent()
        assertEquals(BookshelfGroupIds.ALL, values.last())
    }

    @Test
    fun `分组未加载空列表不回退`() = runTest {
        val saved = MutableStateFlow(1L)
        val groups = MutableStateFlow(emptyList<BookshelfGroupUiModel>())
        val state = BookshelfGroupState(saved, groups, backgroundScope)
        val values = mutableListOf<Long>()
        backgroundScope.launch { state.selected.toList(values) }

        runCurrent()
        assertEquals(1L, values.last())
    }
}
