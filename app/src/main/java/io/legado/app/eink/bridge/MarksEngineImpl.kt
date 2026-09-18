package io.legado.app.eink.bridge

import android.net.Uri
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.contract.MarksEngine
import io.legado.app.eink.contract.MarkingUiModel
import io.legado.app.model.ReadBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import splitties.init.appCtx

/**
 * 书签/笔记端口实现：宿主 bookmarks 单表按 bookText 分区——
 * 空 = 位置书签（书签 Tab），非空 = 文字锚点书签（笔记 Tab 的划线/想法，
 * 与完整模式「选中文字书签」同一条记录，两模式互通）。
 *
 * 完整模式的快速书签带页摘录（bookText 非空），会归入笔记 Tab 展示为
 * 划线卡（单表无判别字段；跳转/删除均可用）；eink 侧快速书签不存摘录，
 * 恒归书签 Tab。
 *
 * 跳转校验为章节标题比对（宿主无源指纹机制）；契约的「本地重定位」
 * 评分机制本宿主未实现——标题不匹配时直接走 NeedConfirm（fallback =
 * 存储坐标），不发起网络。
 *
 * 已知待办（契约改进提案，2026-09-18）：MarksEngine 为整端口二值
 * （注册 = 书签+笔记双 Tab 全上）。建议演进为按能力注册——宿主可声明
 * 仅支持书签 / 仅笔记 / 两者，模块据此隐藏无数据源的 Tab。本宿主即
 * 踩中该缺口：bookmarks 单表无判别字段，完整模式快速书签（页摘录）
 * 会被笔记 Tab 误纳为长摘录划线卡。
 * 能力显隐范围不限于目录页 Tab——阅读界面的相关设置项与入口同需
 * 跟随能力声明，典型如「下拉添加书签」开关（阅读「其它设置」面板，
 * GlobalSettings.pullDownBookmark）：应按宿主的「页面书签」能力显隐，
 * 而非随 ReaderSelectionEngine 整体注册门控（当前划线/想法与页面
 * 书签捆绑注册，仅支持其一的宿主会出现死开关或死入口）；同族还有
 * 顶栏书签钮、页角标、下拉手势、长按选择的划线/想法操作条。
 */
internal object MarksEngineImpl : MarksEngine {

    override fun observeBookmarks(bookUrl: String): Flow<List<BookmarkUiModel>> =
        observeRows(bookUrl) { rows ->
            rows.filter { it.isPageBookmark() }
                .map { row ->
                    BookmarkUiModel(
                        id = row.time,
                        chapterIndex = row.chapterIndex,
                        chapterName = row.chapterName,
                        bookText = row.bookText,
                        content = row.content,
                    )
                }
        }

    override fun observeMarkings(bookUrl: String): Flow<List<MarkingUiModel>> =
        observeRows(bookUrl) { rows ->
            rows.filter { !it.isPageBookmark() }
                .map { row ->
                    MarkingUiModel(
                        id = row.time.toString(),
                        chapterIndex = row.chapterIndex,
                        chapterName = row.chapterName,
                        chapterPos = row.chapterPos,
                        selectedText = row.bookText,
                        note = row.content,
                        thought = row.content.isNotEmpty(),
                        createdAt = row.time,
                    )
                }
        }

    /** 按 bookUrl 解析书籍后订阅书签流（按章节、章内位置升序分区映射）。 */
    private fun <T> observeRows(
        bookUrl: String,
        transform: (List<Bookmark>) -> List<T>,
    ): Flow<List<T>> {
        val book = runCatching { appDb.bookDao.getBook(bookUrl) }.getOrNull()
            ?: return flowOf(emptyList())
        return appDb.bookmarkDao.flowByBook(book.name, book.author)
            .map { rows ->
                transform(rows.sortedWith(compareBy({ it.chapterIndex }, { it.chapterPos }, { it.time })))
            }
    }

    override suspend fun resolveBookmarkJump(bookmarkId: Long): JumpResolution {
        val row = sessionRow(bookmarkId) ?: return JumpResolution.Failed("书签不存在")
        return resolveJump(row)
    }

    override suspend fun resolveMarkingJump(markingId: String): JumpResolution {
        val id = markingId.toLongOrNull()
            ?: return JumpResolution.Failed("标记不存在")
        val row = sessionRow(id) ?: return JumpResolution.Failed("标记不存在")
        return resolveJump(row)
    }

    /** 章节标题比对校验（Match→Located；不匹配/越界→NeedConfirm 回落存储坐标）。 */
    private suspend fun resolveJump(row: Bookmark): JumpResolution {
        val book = ReadBook.book ?: return JumpResolution.Failed("无阅读会话")
        if (row.chapterIndex >= book.totalChapterNum) {
            return JumpResolution.NeedConfirm(
                "书签指向第${row.chapterIndex + 1}章，当前目录共${book.totalChapterNum}章（换源或目录变化），仍跳转？",
                JumpResolution.Located(row.chapterIndex, row.chapterPos),
            )
        }
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, row.chapterIndex)
        return if (chapter?.title == row.chapterName) {
            JumpResolution.Located(row.chapterIndex, row.chapterPos)
        } else {
            JumpResolution.NeedConfirm(
                "书签章节「${row.chapterName}」与当前目录不一致（换源或目录更新），仍按原位置跳转？",
                JumpResolution.Located(row.chapterIndex, row.chapterPos),
            )
        }
    }

    override suspend fun exportMarkingsMarkdown(bookUrl: String, uri: String): Boolean {
        val book = runCatching { appDb.bookDao.getBook(bookUrl) }.getOrNull() ?: return false
        val markings = appDb.bookmarkDao.getByBook(book.name, book.author)
            .filter { !it.isPageBookmark() }
            .sortedWith(compareBy({ it.chapterIndex }, { it.chapterPos }))
        if (markings.isEmpty()) return false
        val markdown = buildString {
            appendLine("# 《${book.name}》笔记")
            appendLine()
            appendLine("- 作者：${book.author}")
            appendLine("- 导出时间：${io.legado.app.constant.AppConst.timeFormat.format(java.util.Date())}")
            var lastChapter = Int.MIN_VALUE
            for (marking in markings) {
                if (marking.chapterIndex != lastChapter) {
                    lastChapter = marking.chapterIndex
                    appendLine()
                    appendLine("## ${marking.chapterName}")
                    appendLine()
                }
                marking.bookText.lines().forEach { appendLine("> $it") }
                if (marking.content.isNotEmpty()) {
                    appendLine()
                    appendLine(marking.content)
                }
                appendLine()
            }
        }
        return try {
            appCtx.contentResolver.openOutputStream(Uri.parse(uri))?.use { output ->
                output.write(markdown.toByteArray())
            } != null
        } catch (_: Exception) {
            false
        }
    }

    /** 当前会话书内按主键取行（目录页跳转解析按会话书限定）。 */
    private suspend fun sessionRow(bookmarkId: Long): Bookmark? {
        val book: Book = ReadBook.book ?: return null
        return appDb.bookmarkDao.getByBook(book.name, book.author)
            .firstOrNull { it.time == bookmarkId }
    }
}
