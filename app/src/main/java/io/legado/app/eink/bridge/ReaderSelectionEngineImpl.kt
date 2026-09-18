package io.legado.app.eink.bridge

import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.eink.contract.ReaderMarkingDetail
import io.legado.app.eink.contract.ReaderSelectionCommit
import io.legado.app.eink.contract.ReaderSelectionEngine
import io.legado.app.model.ReadBook
import kotlin.coroutines.cancellation.CancellationException

/** 位置书签判别：bookText 为空 = 快速书签（eink 快速书签不存摘录）。 */
internal fun Bookmark.isPageBookmark() = bookText.isEmpty()

/**
 * 选区批注端口实现：契约的「划线/想法」映射到宿主 bookmarks 表的
 * 「选中文字书签」（完整模式 ContentTextView.createBookmark 同构：
 * bookText = 选中文本、content = 想法内容、chapterPos = 选区起点——与
 * 模块快照的章内字符位置同一坐标系，直存免搜索）。
 *
 * 存储编码（单表无样式字段）：
 *  - markingId = Bookmark.time 主键字符串；
 *  - thought = content 非空（想法必有文字、划线无笔记；空文本想法会
 *    读回为划线——单表映射的取舍）；
 *  - 端点区间不落库：划线渲染按「起点 + bookText 长度」近似还原
 *    （见 ReaderPageSnapshotMapper.decorationSpans）。
 *
 * 划线渲染：宿主完整模式不渲染书签下划线；墨水屏由模块渲染侧承接，
 * 映射器把当前章文字书签转为行装饰 run（实线 = 划线、虚线 = 想法），
 * 落库/删除后重排当前章（保持页内位置），新快照携带装饰推送——与
 * 完整模式的差异是有意的墨水屏增强（真机反馈需要划线可见可点）。
 */
internal object ReaderSelectionEngineImpl : ReaderSelectionEngine {

    /**
     * 页面书签能力：本宿主不支持——书签模型为「选中文字书签」（即划线/
     * 笔记，经笔记 Tab 管理），无独立的页面级快速书签管理面。声明不支持
     * 后模块隐藏下拉书签手势、顶栏书签钮、页角标与「下拉添加书签」开关
     * （togglePageBookmark 实现保留但不可达）。
     */
    override val supportsPageBookmark: Boolean
        get() = false

    override suspend fun saveMarking(commit: ReaderSelectionCommit): Boolean {
        val book = ReadBook.book ?: return false
        return try {
            // 同锚点 upsert（锚点 = 章节 + 选区起点；限文字书签不吞位置书签）
            val existing = appDb.bookmarkDao.getByBook(book.name, book.author)
                .firstOrNull {
                    !it.isPageBookmark() &&
                        it.chapterIndex == commit.chapterIndex &&
                        it.chapterPos == commit.start
                }
            if (existing != null) {
                existing.bookText = commit.selectedText
                existing.content = commit.note
                appDb.bookmarkDao.update(existing)
            } else {
                appDb.bookmarkDao.insert(
                    Bookmark(
                        bookName = book.name,
                        bookAuthor = book.author,
                        chapterIndex = commit.chapterIndex,
                        chapterPos = commit.start,
                        chapterName = ReadBook.curTextChapter?.title ?: "",
                        bookText = commit.selectedText,
                        content = commit.note,
                    )
                )
            }
            // 契约：落库后触发当前章重排（保持页内位置）——新快照携带划线
            // 装饰推送，模块的待确认预览随 pageVersion 推进正常收尾
            ReaderEngineImpl.relayout()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun deleteMarking(markingId: String): Boolean {
        val marking = findBookmarkRow(markingId) ?: return false
        return try {
            appDb.bookmarkDao.delete(marking)
            ReaderEngineImpl.relayout()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun findMarking(markingId: String): ReaderMarkingDetail? {
        val marking = findBookmarkRow(markingId) ?: return null
        return marking.toMarkingDetail()
    }

    override suspend fun togglePageBookmark(): Boolean? {
        val book = ReadBook.book ?: return null
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex) ?: return null
        val samePage = appDb.bookmarkDao.getByBook(book.name, book.author)
            .filter {
                it.isPageBookmark() &&
                    it.chapterIndex == ReadBook.durChapterIndex &&
                    page.containPos(it.chapterPos)
            }
        return try {
            if (samePage.isNotEmpty()) {
                // 同页多条时删最近一条（契约快速书签语义）
                appDb.bookmarkDao.delete(samePage.maxBy { it.time })
                ReaderEngineImpl.relayout()
                false
            } else {
                appDb.bookmarkDao.insert(
                    book.createBookMark().apply {
                        chapterIndex = ReadBook.durChapterIndex
                        chapterPos = ReadBook.durChapterPos
                        chapterName = page.title
                        // 不存页摘录：空 bookText = 位置书签（不进笔记 Tab，
                        // 与「选中文字书签 = 划线」的分区判别一致）
                        bookText = ""
                    }
                )
                ReaderEngineImpl.relayout()
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** 当前会话书内按主键取行（markingId = time.toString()）。 */
    internal suspend fun findBookmarkRow(markingId: String): Bookmark? {
        val book = ReadBook.book ?: return null
        val id = markingId.toLongOrNull() ?: return null
        return appDb.bookmarkDao.getByBook(book.name, book.author)
            .firstOrNull { it.time == id }
    }

    internal fun Bookmark.toMarkingDetail() = ReaderMarkingDetail(
        selectedText = bookText,
        note = content,
        thought = content.isNotEmpty(),
    )
}
