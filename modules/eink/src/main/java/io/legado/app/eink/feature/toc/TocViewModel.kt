package io.legado.app.eink.feature.toc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.ChapterUiModel
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.contract.PendingJumpConfirm
import io.legado.app.eink.contract.TocBookUiModel
import io.legado.app.eink.contract.TocFetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 目录 UiState。
 */
data class TocUiState(
    val book: TocBookUiModel? = null,
    val chapters: List<ChapterUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val isReversed: Boolean = false,
    val searchKey: String = "",
    val error: String? = null,
    /** 已缓存章节的文件名集合（未缓存章节显示图标，参考 View 版） */
    val cachedFileNames: Set<String> = emptySet(),
    val isLocalBook: Boolean = false,
    /** 底部操作栏当前 Tab。 */
    val selectedTab: TocTab = TocTab.Chapters,
    /** 书签 Tab 列表（marksEngine 可用时由 observeBookmarks 维护）。 */
    val bookmarks: List<BookmarkUiModel> = emptyList(),
    /** marksEngine 是否注册（false = 不渲染书签 Tab）。 */
    val marksAvailable: Boolean = false,
    /** 跳转确认弹层（null = 无）。 */
    val pendingJump: PendingJumpConfirm? = null,
) {

    /** 当前阅读章节索引 */
    val currentChapterIndex: Int
        get() = book?.currentChapterIndex ?: 0

    /** 过滤后的章节（搜索时按标题过滤） */
    val displayChapters: List<ChapterUiModel>
        get() = if (searchKey.isBlank()) chapters else {
            chapters.filter { it.title.contains(searchKey, ignoreCase = true) }
        }

    val isEmpty: Boolean
        get() = !isLoading && displayChapters.isEmpty()
}

/** 目录页底部操作栏 Tab（marksEngine 缺失时无 Tab，单列表现状）。 */
enum class TocTab { Chapters, Bookmarks }

/**
 * 目录 ViewModel。
 *
 * 数据加载经 TocEngine 端口（宿主 DAO/联网拉取管线），VM 保留加载
 * 状态机与错误文案。
 */
class TocViewModel(application: Application) : AndroidViewModel(application) {

    private val engine get() = EInkEngineRegistry.tocEngine

    private val marksEngine get() = EInkEngineRegistry.marksEngine

    private val _uiState = MutableStateFlow(TocUiState())
    val uiState: StateFlow<TocUiState> = _uiState.asStateFlow()

    private val _jumpTarget = MutableSharedFlow<JumpResolution.Located>(extraBufferCapacity = 16)
    /** 已解析成功的跳转目标（Route 层执行引擎跳转 + 导航）。 */
    val jumpTarget: SharedFlow<JumpResolution.Located> = _jumpTarget.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** 用户可见提示（Failed 文案等，Route 层 toast）。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var loadedBookUrl: String? = null

    fun loadBook(bookUrl: String) {
        if (loadedBookUrl == bookUrl) {
            // 同书重进（阅读返回后再进目录）：刷新缓存标记集合
            refreshCacheFiles()
            return
        }
        loadedBookUrl = bookUrl
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            // 书架记录优先；未加书架的搜索书由宿主转 notShelf 隐藏行入库
            //（不显示于书架，与 View 版"未加书架直接阅读"行为一致），
            // 使进度与目录缓存可写
            val book = engine.resolveBook(bookUrl)
            if (book == null) {
                _uiState.update { it.copy(isLoading = false, error = "书籍不存在") }
                return@launch
            }
            // 书解析成功：登记 marks 能力并订阅书签流（独立 launch，不阻塞目录加载）
            _uiState.update { it.copy(marksAvailable = marksEngine != null) }
            marksEngine?.let { marks ->
                viewModelScope.launch {
                    marks.observeBookmarks(book.bookUrl).collect { list ->
                        _uiState.update { it.copy(bookmarks = list) }
                    }
                }
            }
            var chapters = engine.loadChapters(bookUrl)
            if (chapters.isEmpty() && !book.isLocal) {
                // 目录缺失（未阅读过的新书）：从书源拉取入库
                when (val result = engine.fetchChaptersFromSource(bookUrl)) {
                    is TocFetchResult.NoSource -> {
                        _uiState.update { it.copy(isLoading = false, error = "没有书源") }
                        return@launch
                    }

                    is TocFetchResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "目录加载失败：${result.cause.localizedMessage}"
                            )
                        }
                        return@launch
                    }

                    is TocFetchResult.Success -> chapters = result.chapters
                }
            }
            _uiState.update {
                it.copy(
                    book = book,
                    chapters = chapters,
                    isLoading = false
                )
            }
            refreshCacheFiles()
        }
    }

    /** 收集已缓存章节文件名集合（本地书为空集合，经 isLocalBook 视为全部已缓存）。 */
    private fun refreshCacheFiles() {
        val book = _uiState.value.book ?: return
        val bookUrl = book.bookUrl
        viewModelScope.launch(Dispatchers.IO) {
            val cached = engine.cachedChapterFileNames(bookUrl)
            _uiState.update {
                it.copy(cachedFileNames = cached, isLocalBook = book.isLocal)
            }
        }
    }

    /** 倒序/正序切换 */
    fun toggleReverse() {
        _uiState.update { it.copy(isReversed = !it.isReversed) }
    }

    /** 章节标题过滤 */
    fun search(key: String) {
        _uiState.update { it.copy(searchKey = key) }
    }

    /**
     * 跳转到指定章节：经端口写回进度（从第 1 页开始，重置页内位置），
     * 完成后回调（用于进入阅读页，保证阅读页读取到已更新的进度）。
     */
    fun openChapter(index: Int, onSaved: (() -> Unit)? = null) {
        if (_uiState.value.book == null) return
        val bookUrl = _uiState.value.book!!.bookUrl
        viewModelScope.launch(Dispatchers.IO) {
            val chapter = _uiState.value.chapters.getOrNull(index) ?: return@launch
            engine.saveReadingProgress(bookUrl, index, chapter.title)
            _uiState.update { it.copy(book = it.book?.copy(currentChapterIndex = index)) }
            onSaved?.invoke()
        }
    }

    /** 底部操作栏 Tab 切换。 */
    fun selectTab(tab: TocTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    /** 书签点击：解析跳转三分支分派（直接跳/弹确认/提示失败）。 */
    fun onBookmarkClick(id: Long) {
        val marks = marksEngine ?: return
        viewModelScope.launch {
            when (val r = marks.resolveBookmarkJump(id)) {
                is JumpResolution.Located -> _jumpTarget.tryEmit(r)
                is JumpResolution.NeedConfirm -> _uiState.update {
                    it.copy(pendingJump = PendingJumpConfirm(r.message, r.fallback))
                }
                is JumpResolution.Failed -> _messages.tryEmit(r.message)
            }
        }
    }

    /** 「仍跳转」确认：清除弹层并按 fallback 坐标发出跳转。 */
    fun confirmPendingJump() {
        val pending = _uiState.value.pendingJump ?: return
        _uiState.update { it.copy(pendingJump = null) }
        pending.fallback?.let { _jumpTarget.tryEmit(it) }
    }

    /** 取消跳转确认弹层。 */
    fun dismissPendingJump() {
        _uiState.update { it.copy(pendingJump = null) }
    }
}
