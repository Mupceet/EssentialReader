package io.legado.app.eink.feature.note

import io.legado.app.eink.contract.MarkingUiModel
import org.junit.Assert.*
import org.junit.Test

class NoteUiStateTest {

    @Test
    fun `默认态加载中且无确认`() {
        val s = NoteUiState()
        assertTrue(s.isLoading)
        assertTrue(s.markings.isEmpty())
        assertNull(s.pendingJump)
        assertFalse(s.canExport)
    }

    @Test
    fun `有笔记即可导出`() {
        val s = NoteUiState(
            isLoading = false,
            markings = listOf(MarkingUiModel("a", 0, "第一章", "文", "", false, 1L)),
        )
        assertTrue(s.canExport)
    }

    @Test
    fun `导出中不可导出`() {
        val s = NoteUiState(
            isLoading = false,
            exporting = true,
            markings = listOf(MarkingUiModel("a", 0, "第一章", "文", "", false, 1L)),
        )
        assertFalse(s.canExport)
    }
}
