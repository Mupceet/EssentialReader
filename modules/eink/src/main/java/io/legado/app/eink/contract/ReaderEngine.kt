package io.legado.app.eink.contract


/**
 * 阅读会话书籍的展示快照。
 *
 * 宿主实现义务：字段从宿主阅读引擎当前持有的书籍实体即时读取；
 * [isInBookshelf] 为 false 表示书籍以「未加书架的隐藏行」存在（引擎仍可
 * 为其写入进度与目录缓存）。快照每次访问重新构造，不要求缓存。
 */
interface ReaderBookSnapshot {
    /** 书籍在引擎侧的不透明身份，回传给端口方法使用。 */
    val handle: BookHandle
    val bookUrl: String
    val name: String
    val author: String
    /** true = 本地导入书（无书源，不联网拉取目录/正文）。 */
    val isLocal: Boolean
    /** true = 已加入书架；false = 未加书架的隐藏行。 */
    val isInBookshelf: Boolean
}

/**
 * 页眉/页脚的可见性判定结果。
 *
 * 宿主实现义务：按自己的阅读界面规则计算（如状态栏隐藏联动、页眉页脚
 * 显示模式）。模块只消费结果，不开放设置。
 */
data class ReaderHeaderFooterVisibility(
    val headerVisible: Boolean,
    val footerVisible: Boolean,
)

/**
 * [ReaderEngine.prepareBookData] 的结果。
 *
 * 宿主实现义务：方法本身不得抛异常，一切失败经本类型返回；
 * [cause] 用于模块侧错误文案。
 */
sealed interface ReaderPrepareResult {
    data object Success : ReaderPrepareResult
    /** 书籍没有可用书源（本地书损坏、书源已删除等）。 */
    data object NoSource : ReaderPrepareResult
    /** 拉取书籍详情（书名/作者/目录地址）失败。 */
    data class BookInfoFailure(val cause: Throwable) : ReaderPrepareResult
    /** 拉取或入库目录失败。 */
    data class TocFailure(val cause: Throwable) : ReaderPrepareResult
}

/**
 * 阅读引擎 → 模块的事件回调。
 *
 * 宿主实现义务：把宿主阅读引擎的状态推送转发到当前注册的回调
 * （[ReaderEngine.register]/[ReaderEngine.unregister] 管理）。无注册者时
 * 事件直接丢弃，不得缓存重放。回调线程不限定，模块侧自行切主线程。
 */
interface ReaderEngineCallback {

    /** 宿主请求展示阅读菜单（如引擎内部的呼出菜单时机）。 */
    fun onRequestShowMenu()

    /** 目录列表加载/更新完成（进入阅读、追更换目录后）。 */
    fun onLoadChapterList(book: ReaderBookSnapshot)

    /**
     * 当前章节正文就绪（首载、翻章、重排后均触发）。
     *
     * @param relativePosition 内容相对视口的滚动位置（0 = 顶部），
     *   宿主无对应概念时传 0。
     * @param resetPageOffset true = 本次内容是全新章节，页内位置应重置。
     * @param success 宿主完成自身收尾后调用的通知（无则传 null）。
     */
    fun onContentUpdated(relativePosition: Int, resetPageOffset: Boolean, success: (() -> Unit)?)

    /** 页内位置变化（翻页、跳页——进度条与页码刷新）。 */
    fun onPageChanged()

    /** 内容加载流程结束（成功与否都触发，加载态收起用）。 */
    fun onContentLoadFinish()

    /** 排版引擎抛出异常（模块展示错误文案并中止当前章渲染）。 */
    fun onLayoutException(e: Throwable)

    /** 书籍记录被引擎侧变更（换源、重定向替换），模块应刷新书籍展示。 */
    fun onNotifyBookChanged()
}

/**
 * 阅读器端口：宿主阅读引擎（会话状态机 + 排版引擎）面向模块的转发面。
 *
 * 职责边界：模块阅读页 VM 保留全部界面编排（菜单状态、翻页交互、调参
 * 防抖、自动翻页定时、电量/时钟刷新）；宿主实现负责书籍会话的装载与
 * 进度落库、正文/目录的网络管线、排版与分页、章节缓存。
 *
 * 实现纪律：
 *  - 排版参数经 [applyStyle]/[currentStyle] 以 [ReaderTextStyle] 快照
 *    整体读写，宿主把它映射为自己的排版配置并持久化；
 *  - 排版产物（页内容）经宿主映射为 [ReaderPageSnapshot] 供
 *    [currentPage] 返回——坐标是宿主排版引擎的测量结果，模块画布按
 *    同值绘制；
 *  - suspend 方法在调用方提供的协程上下文执行，宿主内部自行调度 IO；
 *  - 非 suspend 的会话/翻页/排版方法在主线程调用，宿主实现应保持
 *    同步快速返回（重活内部转异步，完成后经回调推送）。
 */
interface ReaderEngine {

    // ---- 回调注册与进度持久化 ----

    /** 注册事件回调（阅读页 VM 构造期调用；重复注册以后注册者为准）。 */
    fun register(callback: ReaderEngineCallback)

    /** 注销回调（阅读页 VM onCleared 调用）。 */
    fun unregister(callback: ReaderEngineCallback)

    /** 当前注册的回调是否仍是 [callback]（VM onCleared 前的竞态判断）。 */
    fun isRegistered(callback: ReaderEngineCallback): Boolean

    /**
     * 将当前会话阅读进度落库（章节下标、页内位置、章节标题、阅读时间）。
     * 模块在章节切换、VM 销毁等时机调用；宿主应同步更新书籍记录的
     * 「最近阅读时间」，它是书架排序依据。
     */
    fun saveReadingProgress()

    // ---- 会话只读状态 ----

    /** 当前会话书籍快照（无会话返回 null）。 */
    val sessionBook: ReaderBookSnapshot?

    /**
     * 引擎当前持有书籍的 bookUrl。换源/重定向替换记录后它与导航参数
     * 可能不同——模块以本值为准刷新界面。
     */
    val sessionBookUrl: String?

    /** 当前书籍的章节总数（未装载目录时 0）。 */
    val chapterSize: Int

    /** 当前阅读章节下标（0-based）。 */
    val currentChapterIndex: Int

    /** 当前章节内页序（0-based；未排版时 0）。 */
    val currentPageIndex: Int

    /** 引擎错误消息（null = 无；模块经 [clearEngineMessage] 清除）。 */
    val engineMessage: String?

    /** 当前章节是否已完成排版且产出页面（false 时模块显示加载/错误态）。 */
    val hasLaidOutPages: Boolean

    /** 当前章节总页数（未排版时 0）。 */
    val currentChapterPageSize: Int

    /** 当前页快照（排版未就绪返回 null）。 */
    fun currentPage(): ReaderPageSnapshot?

    // ---- 会话控制 ----

    /**
     * 装载书籍到阅读会话：读取进度与目录缓存，触发首章排版。
     * 用于常规打开（模块已在装载前调 [prepareBookData] 补齐数据）。
     */
    fun loadBook(book: BookHandle)

    /**
     * 全量重载书籍（引擎内会话数据作废重建）：书籍记录被替换或内容
     * 变更后使用（换源、目录重定向）。与 [loadBook] 的区别在于不复用
     * 引擎内旧状态。
     */
    fun reloadBook(book: BookHandle)

    /** 同步会话书籍的书架标记（加架/移出后调用）。 */
    fun setInBookshelf(value: Boolean)

    /** 清除 [engineMessage]。 */
    fun clearEngineMessage()

    /** 装载当前章节内容（进度/目录缓存就绪后的首次正文加载）。 */
    fun loadContent(resetPageOffset: Boolean)

    /** 跳转并装载指定章节。 */
    fun loadContent(chapterIndex: Int, resetPageOffset: Boolean)

    /**
     * 联网检查目录更新（追更场景）。宿主实现应含门槛与限频（如同一
     * 短时间窗口内只真正检查一次）；更新结果经
     * [ReaderEngineCallback.onLoadChapterList]/[onNotifyBookChanged] 推送。
     */
    fun refreshToc()

    /**
     * 按 bookUrl 解析阅读书籍：书架已有记录直接返回；未加书架的搜索书
     * 转为隐藏行入库（使进度与目录缓存可写）后返回。找不到返回 null。
     */
    suspend fun resolveBook(bookUrl: String): ReaderBookSnapshot?

    /**
     * 补齐阅读前置数据：网络书缺目录地址先拉详情；目录缺失或本地书
     * 变更时重新拉取目录并入库（含重定向替换书架记录）。不抛异常，
     * 失败经 [ReaderPrepareResult] 返回。
     */
    suspend fun prepareBookData(book: BookHandle): ReaderPrepareResult

    // ---- 翻页 ----

    /** 下一页（章尾自动进入下一章），无更多页返回 false。 */
    fun nextPage(): Boolean

    /** 上一页（章首自动回到上一章末页），无更多页返回 false。 */
    fun prevPage(): Boolean

    /** 跳转到当前章节指定页（0-based，越界由引擎钳制）。 */
    fun skipToPage(pageIndex: Int)

    /** 跳转到下一章，无下一章返回 false。 */
    fun nextChapter(): Boolean

    /** 跳转到上一章，无上一章返回 false。 */
    fun prevChapter(): Boolean

    /**
     * 自动翻页间隔（秒）。与宿主阅读引擎的自动阅读速度共用同一配置
     * （默认 10，有效范围 1..120），两模式调参互相同步。
     */
    val autoReadIntervalSec: Int

    /** 写入自动翻页间隔（秒）并持久化。 */
    suspend fun setAutoReadIntervalSec(value: Int)

    // ---- 章节操作 ----

    /** 清除当前章内容缓存并重新加载（正文损坏/编码错乱时用户触发）。 */
    suspend fun refreshCurrentChapter()

    /**
     * 启动章节缓存下载（当前章起向后 [count] 章；[cacheAll] 为全本）。
     * @return null = 无会话书或章节已全部缓存；false = 本地书无需缓存；
     * true = 已启动
     */
    fun startCache(count: Int, cacheAll: Boolean): Boolean?

    /**
     * 将当前会话书籍加入书架（仅对未加书架的隐藏行有效）。
     * @return null = 无会话书或已在书架；true/false = 保存成功/失败
     */
    suspend fun addSessionBookToShelf(): Boolean?

    /**
     * 将当前会话书籍移出书架（退回隐藏行，不物理删除）。
     * @return null = 无会话书或本就未在书架；true/false = 保存成功/失败
     */
    suspend fun removeSessionBookFromShelf(): Boolean?

    // ---- 排版 ----

    /**
     * 上报阅读区可用尺寸（px，扣除页眉页脚等内容边距前的整页区域）。
     * 首帧布局与旋转变化时调用；宿主引擎在拿到真实尺寸前无法排版。
     */
    fun updateViewSize(width: Int, height: Int)

    /**
     * 整体写入排版参数：映射到宿主排版配置各字段、持久化并刷新排版
     * 画笔。标题字号无独立字段——宿主实现应把标题字号一并写入同值
     * （模块语义：标题跟随正文字号）。
     */
    fun applyStyle(style: ReaderTextStyle)

    /** 写入正文加粗开关（可变字重路径）；[textBold] 读回当前值。 */
    fun setTextBold(enabled: Boolean)
    val textBold: Boolean

    /** 从宿主排版配置读回当前参数快照。 */
    fun currentStyle(): ReaderTextStyle

    /**
     * 按当前参数重新排版（清空章节排版缓存，从当前进度重排）。
     * 模块在调参防抖合并后调用；宿主完成后经
     * [ReaderEngineCallback.onContentUpdated] 推送新页。
     */
    fun relayout()

    // ---- 触控与页眉页脚 ----

    /** 水平滑动手势判定为翻页的最小距离（px，0 = 使用系统 touch slop）。 */
    val pageTouchSlop: Int

    /** 页眉/页脚可见性判定（规则为宿主阅读界面语义，模块不开放设置）。 */
    fun headerFooterVisibility(): ReaderHeaderFooterVisibility

    /** 当前时间文本（按宿主的用户可见时间格式格式化，页眉时钟用）。 */
    fun formatTimeNow(): String
}
