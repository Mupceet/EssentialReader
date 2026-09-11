package io.legado.app.eink.feature.toc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.ChapterUiModel
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.contract.MarkingUiModel
import io.legado.app.eink.contract.PendingJumpConfirm
import io.legado.app.eink.contract.TocBookUiModel
import io.legado.app.eink.contract.TocFetchResult
import io.legado.app.eink.session.ReaderSessionCache
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
    /** 当前 Tab（标题下三段切换：目录 / 书签 / 笔记）。 */
    val selectedTab: TocTab = TocTab.Chapters,
    /** 书签 Tab 列表（marksEngine 可用时由 observeBookmarks 维护）。 */
    val bookmarks: List<BookmarkUiModel> = emptyList(),
    /** 笔记 Tab 列表（划线 + 想法；marksEngine 可用时由 observeMarkings 维护）。 */
    val markings: List<MarkingUiModel> = emptyList(),
    /** marksEngine 是否注册（false = 不渲染书签/笔记 Tab）。 */
    val marksAvailable: Boolean = false,
    /** 笔记导出进行中（导出入口置灰）。 */
    val exporting: Boolean = false,
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

    /** 笔记 Tab 可导出：有笔记且不在导出中（导出入口置灰依据）。 */
    val canExport: Boolean
        get() = markings.isNotEmpty() && !exporting
}

/**
 * 标题下三段切换（marksEngine 缺失时只剩目录，Tab 行整体不渲染）。
 * 书签 / 笔记按章节聚合展示（见 [TocMarkRow]）。
 */
enum class TocTab { Chapters, Bookmarks, Notes }

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
            // 阅读会话预热命中（进阅读页首章出页后已预热）：直读快照——目录/书签/
            // 笔记首帧即完整，不再等 Room 流往返与章节查询；随后跟会话流跟进更新。
            // 未命中（从详情页直接进目录、换源后旧会话已停、降级宿主）走原有自加载。
            val warm = ReaderSessionCache.snapshot()?.takeIf { it.bookUrl == bookUrl }
            if (warm != null) {
                _uiState.update {
                    it.copy(
                        marksAvailable = warm.marksAvailable,
                        bookmarks = warm.bookmarks,
                        markings = warm.markings,
                    )
                }
                observeSession(bookUrl)
            } else {
                // 书解析成功：登记 marks 能力并订阅书签/笔记流（独立 launch，不阻塞目录加载）
                _uiState.update { it.copy(marksAvailable = marksEngine != null) }
                marksEngine?.let { marks ->
                    viewModelScope.launch {
                        marks.observeBookmarks(book.bookUrl).collect { list ->
                            _uiState.update { it.copy(bookmarks = list) }
                        }
                    }
                    viewModelScope.launch {
                        marks.observeMarkings(book.bookUrl).collect { list ->
                            _uiState.update { it.copy(markings = list) }
                        }
                    }
                }
            }
            // 章节：预热命中直接用快照（未就绪为 null → 按原路径查库）
            var chapters = warm?.chapters ?: engine.loadChapters(bookUrl)
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

    /**
     * 跟进会话缓存：阅读会话的订阅持续推送（书签/笔记增删、目录就绪），
     * 这里只应用**同一本书**的快照；会话结束（退出阅读/换书）后流变 null，
     * 保留既有状态不再更新。
     */
    private fun observeSession(bookUrl: String) {
        viewModelScope.launch {
            ReaderSessionCache.session.collect { snapshot ->
                if (snapshot == null || snapshot.bookUrl != bookUrl) return@collect
                _uiState.update {
                    it.copy(
                        marksAvailable = snapshot.marksAvailable,
                        bookmarks = snapshot.bookmarks,
                        markings = snapshot.markings,
                        chapters = snapshot.chapters ?: it.chapters,
                    )
                }
            }
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

    /** 笔记点击：解析跳转（校验不 Match 时先本地重定位）三分支分派。 */
    fun onMarkingClick(id: String) {
        val marks = marksEngine ?: return
        viewModelScope.launch {
            when (val r = marks.resolveMarkingJump(id)) {
                is JumpResolution.Located -> _jumpTarget.tryEmit(r)
                is JumpResolution.NeedConfirm -> _uiState.update {
                    it.copy(pendingJump = PendingJumpConfirm(r.message, r.fallback))
                }
                is JumpResolution.Failed -> _messages.tryEmit(r.message)
            }
        }
    }

    /** 笔记 Tab 导出 Markdown 到 SAF uri（结果经 messages 反馈）。 */
    fun exportMarkdown(bookUrl: String, uri: String) {
        val marks = marksEngine ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(exporting = true) }
            val ok = marks.exportMarkingsMarkdown(bookUrl, uri)
            _uiState.update { it.copy(exporting = false) }
            _messages.tryEmit(if (ok) "已导出" else "导出失败")
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
