package io.legado.app.eink.contract

import kotlinx.coroutines.flow.Flow

/**
 * [BookshelfEngine.refreshBookToc] 的结果（模块据此累计刷新统计）。
 */
enum class BookshelfTocRefreshResult {
    /** 刷新成功（目录已更新入库，预缓存已入队）。 */
    OK,

    /** 书籍记录已不存在（刷新期间被删除）。 */
    NO_BOOK,

    /** 书籍没有可用书源（本地书损坏、书源已删除等）。 */
    NO_SOURCE,

    /** 拉取/管线异常。 */
    ERROR,
}

/**
 * 书架端口：书架数据流、目录批量刷新与预缓存联动。
 *
 * 书架页与启动流程：
 * ```text
 * 进入书架页
 *  ├─ observeShelf() ─────────► 书籍流 ─► 列表渲染（增删/进度变化自动发射）
 *  ├─ autoRefreshBook 开启
 *  │    └─ updatableBooks() ─► 并发 refreshBookToc(bookUrl) × N
 *  │                            ├─ OK/NO_BOOK/NO_SOURCE/ERROR ─► VM 刷新统计
 *  │                            └─ 成功即预缓存入队（宿主内部）
 *  └─ preDownloadChapterCount > 0
 *       └─ startCacheProcessJob()    预缓存泵循环（模块进程级作用域承载，
 *                                    不随书架 VM 销毁取消）
 *            └─ 刷新期间 setCacheWorkingState(false) 暂停，结束恢复 true
 *
 * 入口模板 onCreate ─► deleteBooksNotInBookshelf()   隐藏行清理（IO）
 * defaultToRead 开启 ─► lastReadBookUrl()            直达阅读解析（同步一次）
 * ```
 *
 * 职责边界：模块书架页 VM 保留界面编排（刷新并发调度、逐条更新中
 * 标记、自动刷新触发、预缓存泵触发）；泵循环由模块进程级作用域承载
 * （非 VM 作用域），离开书架页仍继续消费已入队章节。宿主实现负责单
 * 本书的完整目录刷新管线（详情/预更新脚本 → 拉目录 → 进度同步 → 重
 * 定向替换 → 入库 → 预缓存入队）与书架数据流。
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
     * 调用时机：[EInkHostActivity] 在 onCreate
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
     * 预缓存泵工作态开关：true = 运行，false = 暂停。目录刷新进行中
     * 模块传 false 暂停泵（目录优先），刷新结束后恢复 true。
     */
    fun setCacheWorkingState(working: Boolean)

    /**
     * 启动预缓存处理循环（挂起至泵结束；模块在书架页按
     * [preDownloadChapterCount][GlobalSettings.preDownloadChapterCount]
     * 门槛调用，运行在模块进程级作用域，不随书架页 VM 销毁取消——
     * 已入队章节在离开书架页后仍继续消费）。
     */
    suspend fun startCacheProcessJob()
}
