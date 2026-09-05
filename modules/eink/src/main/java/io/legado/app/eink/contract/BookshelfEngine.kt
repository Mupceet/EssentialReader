package io.legado.app.eink.contract

import kotlinx.coroutines.flow.Flow

/**
 * [BookshelfEngine.refreshBookToc] 的结果（模块据此累计刷新统计）。
 */
enum class BookshelfTocRefreshResult {
    OK, NO_BOOK, NO_SOURCE, ERROR
}

/**
 * 书架端口：书架数据流、目录批量刷新与预缓存联动。
 *
 * 职责边界：模块书架页 VM 保留界面编排（刷新并发调度、逐条更新中
 * 标记、自动刷新触发、预缓存泵循环）；宿主实现负责单本书的完整
 * 目录刷新管线（详情/预更新脚本 → 拉目录 → 进度同步 → 重定向替换
 * → 入库 → 预缓存入队）与书架数据流。
 */
interface BookshelfEngine {

    /**
     * 书架全量书籍流（全部书架分组；含展示字段映射）。书籍增删、
     * 进度/最新章节变化后发射新列表。
     */
    fun observeShelf(): Flow<List<BookshelfItemUiModel>>

    /**
     * 最近阅读书籍的 bookUrl（启动直达阅读解析用）。
     *
     * 调用时机：[io.legado.app.eink.app.EInkHostActivity] 在 onCreate
     * **主线程同步**读取一次（仅当 GlobalSettings.defaultToRead 开启）。
     * 宿主实现应保持单行查询的轻量。无最近阅读返回 null。
     */
    fun lastReadBookUrl(): String?

    /**
     * 物理删除「未加书架的隐藏行」（进入 E-Ink 时由入口模板调用一次；
     * 详情页预取、未加架阅读都会产生这类行）。
     */
    suspend fun deleteBooksNotInBookshelf()

    /** 本次待刷新目录的书籍（非本地且可更新）。 */
    suspend fun updatableBooks(): List<BookshelfItemUiModel>

    /**
     * 刷新单本书目录：阻塞至该书完成；不抛异常，结果经
     * [BookshelfTocRefreshResult] 返回。宿主内部应包含失败标记写入
     * （下次刷新跳过连续失败的书）与预缓存入队。
     */
    suspend fun refreshBookToc(bookUrl: String): BookshelfTocRefreshResult

    /** 预缓存下载队列是否正在工作（书架页泵状态展示）。 */
    val isCacheRunning: Boolean

    /**
     * 预缓存泵运行开关：目录刷新进行中模块会暂停泵（true = 暂停），
     * 刷新结束后恢复。
     */
    fun setCacheWorkingState(working: Boolean)

    /**
     * 启动预缓存处理循环（挂起至泵结束；模块在书架页按
     * [preDownloadChapterCount][GlobalSettings.preDownloadChapterCount]
     * 门槛调用）。
     */
    suspend fun startCacheProcessJob()
}
