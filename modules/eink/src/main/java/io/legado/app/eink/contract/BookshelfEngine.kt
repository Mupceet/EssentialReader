package io.legado.app.eink.contract

import kotlinx.coroutines.flow.Flow

/** 单本书目录刷新结果（驱动 VM 侧 ShelfBench 统计）。 */
enum class BookshelfTocRefreshResult {
    OK, NO_BOOK, NO_SOURCE, ERROR
}

/**
 * 书架端口。
 *
 * E-Ink 书架 VM 保留编排（并发、updating 标记、自动刷新触发、预缓存泵
 * 循环），单本书的目录刷新管线（详情/预更新脚本 → 拉目录 → 进度同步 →
 * 重定向替换 → 入库 → 预缓存入队）是引擎侧编排模式，整体下沉到桥接层
 * （对齐 View 版 MainViewModel.updateToc）。
 */
interface BookshelfEngine {

    /** 书架全量书籍流（Room flowByGroup(IdAll) + UiModel 映射）。 */
    fun observeShelf(): Flow<List<BookshelfItemUiModel>>

    /**
     * 最近阅读书籍的 bookUrl（启动直达阅读解析用）：defaultToRead 开启时
     * 入口模板在 onCreate 主线程同步读取一次，无最近阅读返回 null。
     * 宿主实现应保持单行查询的轻量（对齐 View 版 bookDao.lastReadBook）。
     */
    fun lastReadBookUrl(): String?

    /** 物理删除 notShelf 隐藏行（进入首页时清理，对齐 View 版）。 */
    suspend fun deleteNotShelfBooks()

    /** 本次待刷新目录的书（非本地且可更新）。 */
    suspend fun updatableShelfBooks(): List<BookshelfItemUiModel>

    /**
     * 刷新单本书目录（阻塞至该书完成；不抛异常，结果经
     * [BookshelfTocRefreshResult] 返回）。内部含失败标记 updateError 与
     * 预缓存入队。
     */
    suspend fun refreshBookToc(bookUrl: String): BookshelfTocRefreshResult

    /** 预缓存队列是否有任务（CacheBook.isRun）。 */
    val isCacheRunning: Boolean

    /** 预缓存泵运行开关（目录刷新进行中暂停，对齐 View 版联动）。 */
    fun setCacheWorkingState(working: Boolean)

    /** 启动预缓存处理协程（CacheBook.startProcessJob，挂起至泵结束）。 */
    suspend fun startCacheProcessJob()
}
