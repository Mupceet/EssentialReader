package io.legado.app.eink.session

import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.ChapterUiModel
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.MarkingUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 阅读会话缓存快照（全模块只读）：当前会话书的目录 / 书签 / 笔记，
 * 以及批注端口是否注册（降级宿主）。
 *
 * [chapters] = null 表示尚未就绪（预热在途），调用方可自行按原路径加载。
 */
data class ReaderSessionSnapshot(
    val bookUrl: String,
    val chapters: List<ChapterUiModel>? = null,
    val bookmarks: List<BookmarkUiModel> = emptyList(),
    val markings: List<MarkingUiModel> = emptyList(),
    val marksAvailable: Boolean = false,
)

/**
 * 会话缓存状态机（纯逻辑，无 I/O）：单会话持有 + 同书幂等 + 换书替换 +
 * 迟到的旧书推送丢弃（订阅在途时换书，旧值不得污染新会话）。
 *
 * 拆成独立类便于单测直接驱动（[ReaderSessionCache] 负责端口订阅与调度）。
 */
internal class ReaderSessionStore {

    private val _session = MutableStateFlow<ReaderSessionSnapshot?>(null)
    val session: StateFlow<ReaderSessionSnapshot?> = _session.asStateFlow()

    /** 启动新会话（同书重复调用由调用方短路；此处仍整体替换旧会话数据）。 */
    fun begin(bookUrl: String, marksAvailable: Boolean) {
        _session.value = ReaderSessionSnapshot(
            bookUrl = bookUrl,
            marksAvailable = marksAvailable,
        )
    }

    /** 结束会话（无论哪本书都清空；[stopBookUrl] 守卫由调用方判断）。 */
    fun clear() {
        _session.value = null
    }

    fun isActive(bookUrl: String): Boolean = _session.value?.bookUrl == bookUrl

    fun setChapters(bookUrl: String, chapters: List<ChapterUiModel>) =
        update(bookUrl) { it.copy(chapters = chapters) }

    fun setBookmarks(bookUrl: String, bookmarks: List<BookmarkUiModel>) =
        update(bookUrl) { it.copy(bookmarks = bookmarks) }

    fun setMarkings(bookUrl: String, markings: List<MarkingUiModel>) =
        update(bookUrl) { it.copy(markings = markings) }

    /** 只在当前会话正是 [bookUrl] 时应用（换书后旧订阅的迟到推送丢弃）。 */
    private inline fun update(
        bookUrl: String,
        transform: (ReaderSessionSnapshot) -> ReaderSessionSnapshot,
    ) {
        _session.update { current ->
            if (current?.bookUrl == bookUrl) transform(current) else current
        }
    }
}

/**
 * 阅读会话预热缓存（切片 1）：进阅读页、**首章出页之后**再预热当前会话书的
 * 目录 / 书签 / 笔记，目录页与笔记 Tab 打开时直读快照——首帧即完整，
 * 不再等 Room 流往返与章节列表查询。
 *
 * 与既有 `CacheBookPump` 同一条纪律：
 *  - 进程级作用域承载订阅，**随会话启停、不常驻**（[stop] 取消订阅并清空）；
 *  - 幂等：同书重复 start 直接复用；
 *  - 换书即替换会话，旧书的迟到推送按 bookUrl 丢弃（[ReaderSessionStore]）。
 *
 * 架构边界：
 *  - 只消费**既有端口**（[io.legado.app.eink.contract.TocEngine] /
 *    [io.legado.app.eink.contract.MarksEngine]），不新增宿主契约；
 *  - 缓存是**加速层不是真相**：端口缺失（降级宿主）→ 空数据 + 同现状语义；
 *    目录页冷启动（从详情页直接进目录、或预热未就绪）仍走原有自加载路径；
 *  - 放在非 feature 包（features 不得互相依赖，会话数据也不属于某个 Feature UI）。
 */
internal object ReaderSessionCache {

    private val store = ReaderSessionStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null

    /** 会话快照流：null = 无会话；换书整体替换。 */
    val session: StateFlow<ReaderSessionSnapshot?> get() = store.session

    /** 同步快照：目录页首帧直读（避免一次 Flow 往返）。 */
    fun snapshot(): ReaderSessionSnapshot? = store.session.value

    /**
     * 启动/复用会话预热。[bookUrl] 为引擎当前持有的会话书。
     *
     * 调用时机由阅读 ViewModel 保证：首章出页之后（不与首屏分页抢 I/O）。
     * 线程安全：宿主内容回调可能来自非主线程。
     */
    @Synchronized
    fun start(bookUrl: String) {
        if (bookUrl.isEmpty()) return
        if (store.isActive(bookUrl)) return
        sessionJob?.cancel()
        val marksAvailable = EInkEngineRegistry.marksEngine != null
        store.begin(bookUrl, marksAvailable)
        sessionJob = scope.launch {
            launch { warmChapters(bookUrl) }
            if (!marksAvailable) return@launch
            val marks = EInkEngineRegistry.marksEngine ?: return@launch
            launch { marks.observeBookmarks(bookUrl).collect { store.setBookmarks(bookUrl, it) } }
            launch { marks.observeMarkings(bookUrl).collect { store.setMarkings(bookUrl, it) } }
        }
    }

    /** 结束会话（仅当当前会话正是 [bookUrl]，避免换书后误停新会话）。 */
    @Synchronized
    fun stop(bookUrl: String) {
        if (!store.isActive(bookUrl)) return
        sessionJob?.cancel()
        sessionJob = null
        store.clear()
    }

    /** 目录列表：一次性查询（目录缺失时阅读页的 prepareBookData 已保证入库）。 */
    private suspend fun warmChapters(bookUrl: String) {
        val chapters = runCatching { EInkEngineRegistry.tocEngine.loadChapters(bookUrl) }
            .getOrNull()
            ?: return
        if (chapters.isNotEmpty()) store.setChapters(bookUrl, chapters)
    }
}
