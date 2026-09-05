package io.legado.app.eink.contract


/**
 * [TocEngine.fetchChaptersFromSource] 的结果。
 *
 * 宿主实现义务：方法本身不得抛异常，一切失败经本类型返回；
 * [cause] 用于模块侧错误文案。
 */
sealed interface TocFetchResult {
    data class Success(val chapters: List<ChapterUiModel>) : TocFetchResult

    /** 书籍没有可用书源。 */
    data object NoSource : TocFetchResult
    data class Failure(val cause: Throwable) : TocFetchResult
}

/**
 * 目录端口：目录页的数据来源。
 *
 * 职责边界：模块目录页 VM 保留加载状态机与错误文案；宿主实现负责
 * 书籍解析落库、目录联网拉取与进度写回的完整管线。
 */
interface TocEngine {

    /**
     * 按 bookUrl 解析目录页书籍：书架已有记录直接返回；未加书架的
     * 搜索书转为隐藏行入库（使进度与目录缓存可写）后返回。
     * 找不到返回 null。
     */
    suspend fun resolveBook(bookUrl: String): TocBookUiModel?

    /** 已入库的目录章节（可能为空——由模块决定是否发起联网拉取）。 */
    suspend fun loadChapters(bookUrl: String): List<ChapterUiModel>

    /**
     * 从书源拉取目录并入库：缺目录地址时先拉书籍详情；成功后更新
     * 书籍记录（章节总数、最新章节标题等）。不抛异常，结果经
     * [TocFetchResult] 返回。
     */
    suspend fun fetchChaptersFromSource(bookUrl: String): TocFetchResult

    /**
     * 已缓存到本地的章节文件名集合（用于目录页缓存标记）。
     * 本地书返回空集合（模块视为全部已缓存）。
     */
    suspend fun cachedChapterFileNames(bookUrl: String): Set<String>

    /**
     * 写回阅读进度到指定章节：更新章节下标与标题、重置页内位置、
     * 刷新阅读时间（书架排序依据）。
     */
    suspend fun saveReadingProgress(bookUrl: String, chapterIndex: Int, chapterTitle: String)
}
