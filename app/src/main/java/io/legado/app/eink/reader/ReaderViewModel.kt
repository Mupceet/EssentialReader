package io.legado.app.eink.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 阅读器 UiState。
 *
 * 状态是离散的（规范 §49: page = 100，而不是 pageAnimationProgress = 0.73）。
 */
data class ReaderUiState(
    val bookName: String = "",
    val chapterTitle: String = "",
    val pageText: String = "",
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val chapterIndex: Int = 0,
    val chapterSize: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    /** 页码显示文本，如 "3 / 15" */
    val pageIndicator: String
        get() = if (pageCount > 0) "${pageIndex + 1} / $pageCount" else ""
}

/**
 * 阅读器 ViewModel。
 *
 * 桥接 [ReadBook] 全局单例到 Compose StateFlow。
 * 通过实现 [ReadBook.CallBack] 接收阅读引擎的状态推送。
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application), ReadBook.CallBack {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** 当前 TextChapter，用于翻页操作 */
    private var currentChapter: TextChapter? = null

    /**
     * 初始化并加载指定书籍。
     *
     * @param viewWidth 阅读区域宽度(px)，用于 ChapterProvider 排版
     * @param viewHeight 阅读区域高度(px)
     */
    fun loadBook(bookUrl: String, viewWidth: Int = 0, viewHeight: Int = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            // 初始化排版引擎尺寸（必须先于 loadContent）
            if (viewWidth > 0 && viewHeight > 0) {
                ChapterProvider.upViewSize(viewWidth, viewHeight)
                ChapterProvider.upStyle()
            }

            val book = appDb.bookDao.getBook(bookUrl) ?: run {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "书籍不存在")
                return@launch
            }

            // 注册回调
            ReadBook.register(this@ReaderViewModel)

            val isSameBook = ReadBook.book?.bookUrl == book.bookUrl
            if (isSameBook) {
                ReadBook.upData(book)
            } else {
                ReadBook.resetData(book)
                ReadBook.loadContent(resetPageOffset = true) {
                    // 内容加载完成回调在 contentLoadFinish 中处理
                }
            }
        }
    }

    /**
     * 下一页。
     */
    fun nextPage() {
        val chapter = currentChapter ?: return
        val state = _uiState.value
        if (state.pageIndex < state.pageCount - 1) {
            // 同章节内翻页
            showPage(chapter, state.pageIndex + 1)
        } else {
            // 翻到下一章
            ReadBook.moveToNextChapter(upContent = true)
        }
    }

    /**
     * 上一页。
     */
    fun prevPage() {
        val chapter = currentChapter ?: return
        val state = _uiState.value
        if (state.pageIndex > 0) {
            showPage(chapter, state.pageIndex - 1)
        } else {
            // 翻到上一章最后一页
            ReadBook.moveToPrevChapter(upContent = true, toLast = true)
        }
    }

    private fun showPage(chapter: TextChapter, index: Int) {
        val pages = chapter.pages
        if (index < 0 || index >= pages.size) return
        val page: TextPage = pages[index]
        _uiState.value = _uiState.value.copy(
            pageText = page.text,
            pageIndex = index,
            pageCount = pages.size,
            isLoading = false,
            error = null
        )
        // 保存阅读位置
        ReadBook.book?.let { book ->
            book.durChapterIndex = page.chapterIndex
            book.durChapterPos = 0
            book.durChapterTitle = page.title
        }
    }

    // ==================== ReadBook.CallBack 实现 ====================

    override fun upMenuView() {
        ReadBook.book?.let { book ->
            _uiState.value = _uiState.value.copy(
                bookName = book.name,
                chapterIndex = ReadBook.durChapterIndex,
                chapterSize = ReadBook.chapterSize,
            )
        }
    }

    override fun loadChapterList(book: Book) {
        _uiState.value = _uiState.value.copy(
            bookName = book.name,
            chapterSize = ReadBook.chapterSize,
        )
    }

    override fun upContent(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) {
        val chapter = ReadBook.curTextChapter
        currentChapter = chapter
        if (chapter != null && chapter.pages.isNotEmpty()) {
            val pageIdx = ReadBook.durPageIndex.coerceIn(0, chapter.lastIndex)
            _uiState.value = _uiState.value.copy(
                chapterTitle = chapter.title,
                chapterIndex = ReadBook.durChapterIndex,
                chapterSize = ReadBook.chapterSize,
            )
            showPage(chapter, pageIdx)
            success?.invoke()
        } else if (ReadBook.msg != null) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = ReadBook.msg)
        }
    }

    override suspend fun upContentAwait(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) {
        upContent(relativePosition, resetPageOffset, success)
    }

    override fun pageChanged() {
        val chapter = ReadBook.curTextChapter ?: return
        currentChapter = chapter
        showPage(chapter, ReadBook.durPageIndex)
    }

    override fun contentLoadFinish() {
        upContent()
    }

    override fun upPageAnim(upRecorder: Boolean) {}

    override fun notifyBookChanged() {}

    override fun sureNewProgress(progress: BookProgress) {}

    override fun cancelSelect() {}

    override fun onCleared() {
        super.onCleared()
        ReadBook.unregister(this)
    }
}
