package io.legado.app.eink.bridge

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.BookRepository
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.domain.usecase.BookmarkTargetVerdict
import io.legado.app.domain.usecase.RelocateMarkingTargetUseCase
import io.legado.app.domain.usecase.VerifyBookmarkTargetUseCase
import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.contract.MarkingUiModel
import io.legado.app.eink.contract.MarksEngine
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.bookmark.MarkingExporter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** styleJson → eink 两档图例：虚线（underlineMode 2）= 想法；其余（实线/背景/字体色/空）= 划线。 */
internal fun markingThought(styleJson: String?): Boolean =
    GSON.fromJsonObject<TextProcessStyle>(styleJson).getOrNull()?.underlineMode == 2

/** 「仍跳转」确认文案（模块弹层直接展示）。 */
internal fun confirmJumpMessage(chapterName: String): String =
    "书签创建后目录可能已变化（$chapterName），仍要跳转吗？"

/** eink 书签/笔记端口实现：列表流 + 跳目标解析 + Markdown 导出。 */
object MarksEngineImpl : MarksEngine, KoinComponent {

    private val bookRepository: BookRepository by inject()
    private val verifyUseCase = VerifyBookmarkTargetUseCase()
    private val relocateUseCase = RelocateMarkingTargetUseCase()

    override fun observeBookmarks(bookUrl: String): Flow<List<BookmarkUiModel>> =
        bookFlow(bookUrl) { book ->
            appDb.bookmarkDao.flowByBook(book.name, book.author)
                .map { list -> list.map { it.toUiModel() } }
        }

    override fun observeMarkings(bookUrl: String): Flow<List<MarkingUiModel>> =
        bookFlow(bookUrl) { book ->
            appDb.bookMarkingDao.flowByBook(book.name, book.author)
                .map { list -> list.map { it.toUiModel() } }
        }

    /** 解析书 → 无书发空列表，有书接 DAO 流；书籍记录变化（换源替换）时自动重解析。 */
    private fun <T> bookFlow(
        bookUrl: String,
        source: suspend (Book) -> Flow<List<T>>,
    ): Flow<List<T>> = flow {
        val book = bookRepository.getBook(bookUrl) ?: run {
            emit(emptyList<T>()); return@flow
        }
        emitAll(source(book))
    }

    private fun Bookmark.toUiModel() = BookmarkUiModel(
        id = time, chapterIndex = chapterIndex, chapterName = chapterName,
        bookText = bookText, content = content,
    )

    private fun BookMarking.toUiModel() = MarkingUiModel(
        id = id,
        chapterIndex = chapterIndex ?: 0,
        chapterName = chapterName,
        selectedText = GSON.fromJsonObject<TextProcessAnchor>(anchorJson).getOrNull()
            ?.selectedText ?: "",
        note = note,
        thought = markingThought(styleJson),
        createdAt = createdAt,
    )

    override suspend fun resolveBookmarkJump(bookmarkId: Long): JumpResolution {
        val bookmark = appDb.bookmarkDao.getById(bookmarkId)
            ?: return JumpResolution.Failed("书签不存在")
        val book = bookRepository.getBook(bookmark.bookName, bookmark.bookAuthor)
            ?: return JumpResolution.Failed("书籍不存在")
        val targetTitle = bookRepository.getChapterTitle(book.name, book.author, bookmark.chapterIndex)
        return when (verifyUseCase.verify(book.bookUrl, targetTitle, bookmark.bookUrl, bookmark.chapterName)) {
            BookmarkTargetVerdict.Match ->
                JumpResolution.Located(bookmark.chapterIndex, bookmark.chapterPos)
            else -> JumpResolution.NeedConfirm(
                message = confirmJumpMessage(bookmark.chapterName),
                fallback = JumpResolution.Located(bookmark.chapterIndex, bookmark.chapterPos),
            )
        }
    }

    override suspend fun resolveMarkingJump(markingId: String): JumpResolution {
        val marking = appDb.bookMarkingDao.getById(markingId)
            ?: return JumpResolution.Failed("标记不存在")
        val anchor = GSON.fromJsonObject<TextProcessAnchor>(marking.anchorJson).getOrNull()
            ?: return JumpResolution.Failed("标记数据异常")
        val chapterIndex = marking.chapterIndex ?: anchor.chapterIndex
        val stored = JumpResolution.Located(chapterIndex, anchor.chapterPosition ?: 0)
        val book = bookRepository.getBook(marking.bookName, marking.bookAuthor)
            ?: return JumpResolution.Failed("书籍不存在")
        val targetTitle = bookRepository.getChapterTitle(book.name, book.author, chapterIndex)
        return when (verifyUseCase.verify(book.bookUrl, targetTitle, marking.bookUrl, marking.chapterName)) {
            BookmarkTargetVerdict.Match -> stored
            else -> relocateMarking(book, marking.chapterName, anchor)
                ?.let { JumpResolution.Located(it.chapterIndex, it.chapterPosition) }
                ?: JumpResolution.NeedConfirm(confirmJumpMessage(marking.chapterName), stored)
        }
    }

    /** 本地重定位（对齐 ReadBookmarkNavigateDelegate.relocateMarking：仅本地已缓存章节，不发起网络）。 */
    private suspend fun relocateMarking(
        book: Book,
        chapterName: String,
        anchor: TextProcessAnchor,
    ): RelocateMarkingTargetUseCase.Target? {
        val processor = ContentProcessor.get(book)
        val candidates = bookRepository.getChapters(book.bookUrl)
            .asSequence()
            .filter {
                it.index == anchor.chapterIndex ||
                        (chapterName.isNotBlank() && it.title == chapterName)
            }
            .distinctBy { it.index }
            .mapNotNull { chapter ->
                val rawContent = BookHelp.getContent(book, chapter) ?: return@mapNotNull null
                RelocateMarkingTargetUseCase.Candidate(
                    chapterIndex = chapter.index,
                    content = processor.getContent(book, chapter, rawContent, includeTitle = false)
                        .toString(),
                )
            }
            .toList()
        return relocateUseCase.locate(anchor, candidates)
    }

    override suspend fun exportMarkingsMarkdown(bookUrl: String, uri: String): Boolean {
        val book = bookRepository.getBook(bookUrl) ?: return false
        val markings = appDb.bookMarkingDao.getByBook(book.name, book.author, null)
        if (markings.isEmpty()) return false
        val content = MarkingExporter.formatToMarkdown(book.name, book.author, markings)
        return MarkingExporter.exportToUri(android.net.Uri.parse(uri), content)
    }
}
