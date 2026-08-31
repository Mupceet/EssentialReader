package io.legado.app.eink.engine

import io.legado.app.eink.feature.reader.ReaderTextStyle

/** 阅读会话书籍快照（ReadBook.book 的模块侧投影）。 */
interface ReaderBookSnapshot {
    val handle: BookHandle
    val bookUrl: String
    val name: String
    val author: String
    val isLocal: Boolean
    val isNotShelf: Boolean
}

/** 页眉/页脚可见性（ReadTipConfig + hideStatusBar 的宿主侧计算结果）。 */
data class ReaderTipSpec(
    val headerVisible: Boolean,
    val footerVisible: Boolean,
)

/** 阅读前置数据准备结果（详情拉取 + 目录入库管线）。 */
sealed interface BookPrepResult {
    data object Success : BookPrepResult
    data object NoSource : BookPrepResult
    data class InfoFailure(val cause: Throwable) : BookPrepResult
    data class TocFailure(val cause: Throwable) : BookPrepResult
}

/**
 * 引擎回调（宿主 ReadBook.CallBack 的模块侧投影）。
 *
 * upContent 与 upContentAwait 在模块侧合并为 [onUpContent]；BookProgress
 * 参数的 sureNewProgress 未使用，投影中省略。
 */
interface ReaderEngineCallback {
    fun onUpMenuView()
    fun onLoadChapterList(book: ReaderBookSnapshot)
    fun onUpContent(relativePosition: Int, resetPageOffset: Boolean, success: (() -> Unit)?)
    fun onPageChanged()
    fun onContentLoadFinish()
    fun onLayoutException(e: Throwable)
    fun onNotifyBookChanged()
}

/**
 * 阅读器端口：宿主 ReadBook 全局状态机 + ChapterProvider 排版引擎的
 * 转发面。E-Ink 阅读页 VM 通过本端口驱动引擎并接收状态推送；
 * 排版参数以 [ReaderTextStyle] 快照整体写入（内部映射宿主 ReadBookConfig
 * 各字段），排版产物经宿主映射为 [EInkPageSnapshot] 快照进入模块状态。
 */
interface ReaderEngine {

    // ---- 注册与生命周期 ----

    fun register(callback: ReaderEngineCallback)
    fun unregister(callback: ReaderEngineCallback)

    /** 当前注册的回调是否仍是 [callback]（onCleared 判断用）。 */
    fun isRegistered(callback: ReaderEngineCallback): Boolean

    /** 落库阅读进度（更新 durChapterTime，书架排序依据）。 */
    fun saveRead()

    // ---- 会话只读状态（ReadBook 投影） ----

    val sessionBook: ReaderBookSnapshot?

    /** 引擎当前持有书籍的 bookUrl（换源/重定向后与路由参数可能不同）。 */
    val sessionBookUrl: String?

    val chapterSize: Int
    val durChapterIndex: Int
    val durPageIndex: Int

    /** 引擎错误消息（null = 无）。 */
    val engineMessage: String?

    /** 当前章节是否已排出页面（curTextChapter 非空且 pages 非空）。 */
    val hasLaidOutPages: Boolean

    /** 当前章节总页数（未排版时 0）。 */
    val currentChapterPageSize: Int

    /** 当前页（durPageIndex 对应页；未就绪返回 null）。 */
    fun currentPage(): EInkPageSnapshot?

    // ---- 会话控制 ----

    fun upData(book: BookHandle)
    fun resetData(book: BookHandle)
    fun setInBookshelf(value: Boolean)
    fun clearEngineMessage()
    fun loadContent(resetPageOffset: Boolean)
    fun loadContent(chapterIndex: Int, resetPageOffset: Boolean)

    /** 联网检查目录更新（追更场景，内部含门槛与限频）。 */
    fun upToc()

    /**
     * 解析阅读书籍：书架记录优先；未加书架的搜索书转 notShelf 隐藏行
     * 入库（进度与目录缓存可写）。找不到返回 null。
     */
    suspend fun resolveBook(bookUrl: String): ReaderBookSnapshot?

    /**
     * 补齐阅读前置数据（网络书缺 tocUrl 先拉详情；目录缺失/本地书变更
     * 重新拉取目录并入库，含重定向替换）。不抛异常。
     */
    suspend fun prepareBookData(book: BookHandle): BookPrepResult

    // ---- 翻页 ----

    /** 下一页（章尾自动进入下一章），无更多页返回 false。 */
    fun nextPage(): Boolean

    /** 上一页（章首自动回到上一章末页），无更多页返回 false。 */
    fun prevPage(): Boolean

    // ---- 章节操作 ----

    /** 刷新当前章节（清缓存重载）。 */
    suspend fun refreshCurrentChapter()

    /**
     * 启动章节缓存（当前章起向后 count 章；[cacheAll] 为全本）。
     * @return null = 无会话书或无需缓存；false = 本地书（不缓存）；
     * true = 已启动
     */
    fun startCache(count: Int, cacheAll: Boolean): Boolean?

    /**
     * 将当前会话书籍加入书架（仅 notShelf 书有效）。
     * @return null = 无会话书或已在书架；true/false = 保存成功/失败
     */
    suspend fun addSessionBookToShelf(): Boolean?

    // ---- 排版（ChapterProvider / ReadBookConfig） ----

    /** 阅读区尺寸变化（首帧布局/旋转）。 */
    fun updateViewSize(width: Int, height: Int)

    /** 整体写入排版参数快照（映射宿主 ReadBookConfig 并持久化 + 刷新画笔）。 */
    fun applyStyle(style: ReaderTextStyle)

    fun setTextBold(enabled: Boolean)
    val textBold: Boolean

    /** 从宿主配置读取当前排版快照。 */
    fun currentStyle(): ReaderTextStyle

    /** 清空章节缓存并按当前进度重新排版（调参防抖后触发）。 */
    fun relayout()

    // ---- 触控与页眉页脚 ----

    /** 水平滑动翻页触发距离（px，0 = 系统 touch slop）。 */
    val pageTouchSlop: Int

    /** 页眉/页脚可见性（ReadTipConfig 规则）。 */
    fun readTipVisibility(): ReaderTipSpec

    /** 当前时间文本（宿主时间格式）。 */
    fun formatTimeNow(): String
}
