package io.legado.app.eink.bridge

import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookmarkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * E-Ink 会话的书签位置快照（宿主 `io.legado.app.model.ReaderBookmarkState`
 * 的 E-Ink 自有版本）。
 *
 * E-Ink 模式下宿主 `ReadBookmarkDelegate`/`ReaderBookmarkState` 不初始化，
 * 页角标判定（[ReaderChapterPager.currentPageSnapshot] 的 bookmarkBadge）
 * 必须由桥自建缓存承担：[ReaderEngineImpl] 换书/重载时 [attach] 当前书，
 * 收集 `BookmarkRepository.flowByBook` 后整体替换 positionsByChapter；
 * 快照构建期与 toggle 判定只做纯内存读，不起协程查库。
 *
 * 快照携带书键：换书时先取消旧收集并清空位置表，新书第一条数据到达前
 * [hasBookmarkInRange] 恒 false——上一本残留的旧位置不会误供给新书。
 * 收集侧写入按 attach 时的书键把关，取消竞态下旧收集迟到的最后一次
 * 写入不得覆盖新书的空表。
 */
internal object EInkBookmarkState : KoinComponent {

    private val bookmarkRepository: BookmarkRepository by inject()

    /** 自有作用域：引擎为进程级单例，收集随会话书存活（同分页缓存不清的返回即恢复语义）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前书 `书名 to 作者`（bookmarks 表无 bookUrl 列，关联键同宿主）。 */
    @Volatile
    private var bookKey: Pair<String, String>? = null

    /** chapterIndex → 该章内所有书签的 chapterPos。整体替换，读侧无需加锁。 */
    @Volatile
    private var positionsByChapter: Map<Int, List<Int>> = emptyMap()

    @Volatile
    private var collectJob: Job? = null

    /**
     * 对齐当前会话书（[ReaderEngineImpl.loadBook]/[ReaderEngineImpl.reloadBook]
     * 时调用）：同书幂等不重订；换书取消旧收集、清空位置表后重订
     * `BookmarkRepository.flowByBook`，首批数据到达前角标判定落空分支。
     */
    fun attach(book: Book?) {
        val key = book?.let { it.name to it.author }
        if (key == bookKey) return
        collectJob?.cancel()
        collectJob = null
        bookKey = key
        positionsByChapter = emptyMap()
        if (key == null) return
        collectJob = scope.launch {
            bookmarkRepository.flowByBook(key.first, key.second).collect { bookmarks ->
                // 书键把关：取消竞态下旧收集的迟到写入不得覆盖新书的表
                if (bookKey == key) {
                    positionsByChapter = bookmarks.groupBy({ it.chapterIndex }, { it.chapterPos })
                }
            }
        }
    }

    /**
     * 判定 `[startPos, endPos)` 页位置区间是否落有书签（纯读内存缓存，
     * 与 BookmarkDao.getByChapterRange 同口径：pos >= start 且 pos < end）。
     *
     * @param chapterIndex 章节下标
     * @param startPos 页首字符在章节内的位置（正文空间）
     * @param endPos 页尾之后一个字符的位置
     */
    fun hasBookmarkInRange(chapterIndex: Int, startPos: Int, endPos: Int): Boolean {
        val positions = positionsByChapter[chapterIndex] ?: return false
        return positions.any { it >= startPos && it < endPos }
    }
}
