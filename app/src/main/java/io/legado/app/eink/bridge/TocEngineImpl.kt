package io.legado.app.eink.bridge

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.model.BookChapterCacheInfo
import io.legado.app.eink.contract.ChapterUiModel
import io.legado.app.eink.contract.TocBookUiModel
import io.legado.app.eink.contract.TocEngine
import io.legado.app.eink.contract.TocFetchResult
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.model.CacheBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * 会话级目录缓存项。章节 UI 模型已含缓存文件名（标题 MD5 一次性算好），
 * 重复进入目录免「全表读取 + 逐章 MD5 映射」；[cachedFileNames] 为 null
 * 表示待枚举（缓存下载事件会置回 null，下次调用重新枚举）。
 */
private class TocSessionCache(
    val chapters: List<ChapterUiModel>,
    @Volatile var cachedFileNames: Set<String>?,
)

/**
 * 目录端口实现：书籍解析（书架优先、搜索书 notShelf 落库）与联网拉取
 * 目录管线的转发。
 *
 * 会话级缓存：目录数据在两次进入之间基本静态，而目录 ViewModel 按导航
 * 条目隔离（每次进入全新），宿主侧持有缓存是重复进入即时显示的唯一位置。
 * 失效策略：
 *  - 章节列表：每次进入用轻量 COUNT 比对章数（追更换目录会增删章节），
 *    不匹配即重查入库；
 *  - 缓存文件名：CacheBook 下载成功事件置空，下次调用重新枚举（阅读页
 *    缓存章节后回目录，云图标不陈旧）；
 *  - 拉取目录成功后整体替换缓存项。
 *
 * 本上游差异：getChapterListAwait 返回 Result（getOrThrow 解包）；
 * 章节首查走 getChapterCacheInfoList 轻量投影（url/title/isVolume/index
 * 四列），目录展示不需要全实体行。
 */
internal object TocEngineImpl : TocEngine {

    /** 会话目录缓存（键 = bookUrl；容量上限防多书残留）。 */
    private val sessionCaches = ConcurrentHashMap<String, TocSessionCache>()

    /** 缓存下载成功事件的作用域：文件名缓存失效收集（进程级，事件低频）。 */
    private val cacheEventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        cacheEventScope.launch {
            CacheBook.cacheSuccessFlow.collect { chapter ->
                sessionCaches[chapter.bookUrl]?.let { it.cachedFileNames = null }
            }
        }
    }

    private fun Book.toUiModel() = TocBookUiModel(
        bookUrl = bookUrl,
        name = name,
        currentChapterIndex = durChapterIndex,
        isLocal = isLocal,
    )

    private fun BookChapter.toUiModel() = ChapterUiModel(
        index = index,
        title = title,
        url = url,
        isVolume = isVolume,
        fileName = getFileName(),
    )

    /** 投影行 → 展示模型：按四元组构造实体取缓存文件名（缓存管理页同款）。 */
    private fun BookChapterCacheInfo.toUiModel() = ChapterUiModel(
        index = index,
        title = title,
        url = url,
        isVolume = isVolume,
        fileName = BookChapter(
            url = url,
            title = title,
            isVolume = isVolume,
            index = index,
        ).getFileName(),
    )

    override suspend fun resolveBook(bookUrl: String): TocBookUiModel? {
        // 书架记录优先；未加书架的搜索书转 Book 入库（notShelf，不显示于
        // 书架，与 View 版"未加书架直接阅读"行为一致），使进度与目录缓存可写
        val book = appDb.bookDao.getBook(bookUrl)
            ?: appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()?.apply {
                addType(BookType.notShelf)
                save()
            }
        return book?.toUiModel()
    }

    override suspend fun loadChapters(bookUrl: String): List<ChapterUiModel> {
        sessionCaches[bookUrl]?.let { cached ->
            // 章数比对防追更陈旧：COUNT 查询远轻于全表读取+映射
            if (appDb.bookChapterDao.getChapterCount(bookUrl) == cached.chapters.size) {
                return cached.chapters
            }
            sessionCaches.remove(bookUrl)
        }
        val chapters = appDb.bookChapterDao.getChapterCacheInfoList(bookUrl)
            .map { it.toUiModel() }
        storeCache(bookUrl, TocSessionCache(chapters = chapters, cachedFileNames = null))
        return chapters
    }

    override suspend fun fetchChaptersFromSource(bookUrl: String): TocFetchResult {
        val book = appDb.bookDao.getBook(bookUrl) ?: return TocFetchResult.NoSource
        val source = appDb.bookSourceDao.getBookSource(book.origin)
            ?: return TocFetchResult.NoSource
        return try {
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
            }
            val chapters = WebBook.getChapterListAwait(source, book, true).getOrThrow()
            appDb.bookChapterDao.delByBook(bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            appDb.bookDao.update(book)
            val uiChapters = chapters.map { it.toUiModel() }
            storeCache(bookUrl, TocSessionCache(chapters = uiChapters, cachedFileNames = null))
            TocFetchResult.Success(uiChapters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TocFetchResult.Failure(e)
        }
    }

    override suspend fun cachedChapterFileNames(bookUrl: String): Set<String> {
        val book = appDb.bookDao.getBook(bookUrl) ?: return emptySet()
        if (book.isLocal) return emptySet()
        val entry = sessionCaches[bookUrl]
        if (entry == null) {
            return BookHelp.getChapterFiles(book)
        }
        entry.cachedFileNames?.let { return it }
        val files = BookHelp.getChapterFiles(book)
        entry.cachedFileNames = files
        return files
    }

    override suspend fun saveReadingProgress(
        bookUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
    ) {
        val book = appDb.bookDao.getBook(bookUrl) ?: return
        book.durChapterIndex = chapterIndex
        book.durChapterPos = 0
        book.durChapterTitle = chapterTitle
        book.durChapterTime = System.currentTimeMillis()
        appDb.bookDao.update(book)
    }

    /** 入缓存并淘汰超限旧项（多书会话残留；键序无意义，任意淘汰即可）。 */
    private fun storeCache(bookUrl: String, entry: TocSessionCache) {
        sessionCaches[bookUrl] = entry
        while (sessionCaches.size > CACHE_MAX_ENTRIES) {
            sessionCaches.remove(sessionCaches.keys.first()) ?: break
        }
    }

    /** 缓存容量上限（单本常驻 + 少量切换余量）。 */
    private const val CACHE_MAX_ENTRIES = 4
}
