package io.legado.app.eink.engine

import io.legado.app.eink.toc.ChapterUiModel
import io.legado.app.eink.toc.TocBookUiModel

/** 目录联网拉取结果。 */
sealed interface TocFetchResult {
    data class Success(val chapters: List<ChapterUiModel>) : TocFetchResult
    data object NoSource : TocFetchResult
    data class Failure(val cause: Throwable) : TocFetchResult
}

/**
 * 目录端口。
 *
 * 书籍解析（书架优先、搜索书 notShelf 落库）与联网拉目录管线是引擎侧
 * 编排模式，下沉桥接层；VM 保留加载状态机与错误文案。
 */
interface TocEngine {

    /**
     * 解析目录页书籍：书架记录优先；未加书架的搜索书转 Book 以 notShelf
     * 隐藏行入库（进度与目录缓存可写）。找不到返回 null。
     */
    suspend fun resolveTocBook(bookUrl: String): TocBookUiModel?

    /** 已入库的目录章节（可能为空，由 VM 决定是否联网拉取）。 */
    suspend fun loadChapters(bookUrl: String): List<ChapterUiModel>

    /**
     * 从书源拉取目录并入库（缺 tocUrl 先拉详情；成功后更新书籍记录）。
     * 不抛异常，结果经 [TocFetchResult] 返回。
     */
    suspend fun fetchChaptersFromSource(bookUrl: String): TocFetchResult

    /** 已缓存章节文件名集合（本地书返回空集合）。 */
    suspend fun cachedChapterFileNames(bookUrl: String): Set<String>

    /** 写回阅读进度到指定章节（重置页内位置，更新时间）。 */
    suspend fun saveReadingProgress(bookUrl: String, chapterIndex: Int, chapterTitle: String)
}
