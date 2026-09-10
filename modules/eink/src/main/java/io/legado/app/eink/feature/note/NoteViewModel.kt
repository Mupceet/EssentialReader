package io.legado.app.eink.feature.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.arch.EInkImmutable
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.contract.MarkingUiModel
import io.legado.app.eink.contract.PendingJumpConfirm
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@EInkImmutable
data class NoteUiState(
    /** 书名（顶栏标题）。 */
    val bookName: String = "",
    /** 混合列表（划线+想法，按章+创建时间升序）。 */
    val markings: List<MarkingUiModel> = emptyList(),
    /** 当前阅读章节下标（「回到当前」与当前章标记）。 */
    val currentChapterIndex: Int = 0,
    val isLoading: Boolean = true,
    val exporting: Boolean = false,
    /** 跳转确认弹层（null = 无）。 */
    val pendingJump: PendingJumpConfirm? = null,
) {
    /** 有笔记且未在导出中。 */
    val canExport: Boolean get() = markings.isNotEmpty() && !exporting
}

class NoteViewModel : ViewModel() {

    private val marksEngine get() = EInkEngineRegistry.marksEngine
    private val tocEngine get() = EInkEngineRegistry.tocEngine

    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    private val _jumpTarget = MutableSharedFlow<JumpResolution.Located>(extraBufferCapacity = 16)
    val jumpTarget: SharedFlow<JumpResolution.Located> = _jumpTarget.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun loadBook(bookUrl: String) {
        val engine = marksEngine ?: run {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        viewModelScope.launch {
            val book = tocEngine.resolveBook(bookUrl)
            if (book == null) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    bookName = book.name,
                    currentChapterIndex = book.currentChapterIndex,
                    isLoading = false,
                )
            }
            engine.observeMarkings(bookUrl).collect { list ->
                _uiState.update { it.copy(markings = list) }
            }
        }
    }

    fun onMarkingClick(id: String) {
        val engine = marksEngine ?: return
        viewModelScope.launch {
            when (val r = engine.resolveMarkingJump(id)) {
                is JumpResolution.Located -> _jumpTarget.tryEmit(r)
                is JumpResolution.NeedConfirm -> _uiState.update {
                    it.copy(pendingJump = PendingJumpConfirm(r.message, r.fallback))
                }
                is JumpResolution.Failed -> _messages.tryEmit(r.message)
            }
        }
    }

    fun confirmPendingJump() {
        val pending = _uiState.value.pendingJump ?: return
        _uiState.update { it.copy(pendingJump = null) }
        pending.fallback?.let { _jumpTarget.tryEmit(it) }
    }

    fun dismissPendingJump() {
        _uiState.update { it.copy(pendingJump = null) }
    }

    /** 导出 Markdown 到 SAF uri（结果经 messages 反馈，exporting 期间置灰按钮）。 */
    fun exportMarkdown(bookUrl: String, uri: String) {
        val engine = marksEngine ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(exporting = true) }
            val ok = engine.exportMarkingsMarkdown(bookUrl, uri)
            _uiState.update { it.copy(exporting = false) }
            _messages.tryEmit(if (ok) "已导出" else "导出失败")
        }
    }
}
