package io.legado.app.eink.feature.bookshelf

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.R
import io.legado.app.eink.arch.UserMessage
import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfShelfEntryUiModel
import io.legado.app.eink.contract.BookshelfStyle
import io.legado.app.eink.contract.BookshelfTocRefreshResult
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.util.onEachParallel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * 书架屏幕 UiState（扁平布尔标志位，参考 JBusDriver UDF 模式）。
 *
 * [entries] 为预抽取的稳定条目（[BookshelfShelfEntryUiModel]，书籍与
 * 用户组聚合两形态），条目参数全稳定类型且数据未变时可跳过重组。
 */
data class BookshelfUiState(
    /** 顶层混合条目（组条目置顶 + 书籍）；组视图为纯书籍条目。 */
    val entries: List<BookshelfShelfEntryUiModel> = emptyList(),
    val isLoading: Boolean = true,
    /** 目录刷新进行中（首页头部刷新按钮禁用并置灰） */
    val isRefreshing: Boolean = false,
    /** 正在更新目录的书籍 bookUrl 集合（对应行角标替换为刷新态） */
    val updatingBookUrls: Set<String> = emptySet(),
    /** 书架网格布局（true = 网格，列数按屏宽自适应；false = 列表）。默认网格。 */
    val isGridLayout: Boolean = true,

    /** 书架显示样式快照（宿主设置投影，实时档）。 */
    val style: BookshelfStyle = BookshelfStyle(),

    /** 精选筛选器列表（筛选面板数据源）。 */
    val filters: List<BookshelfGroupUiModel> = emptyList(),

    /** 当前筛选（ALL = 无筛选）。 */
    val selectedFilterId: Long = BookshelfGroupIds.ALL,

    /** 当前范围（ALL = 顶层；用户组 id = 组视图）。 */
    val selectedScopeId: Long = BookshelfGroupIds.ALL,

    /** 组视图时顶栏标题显示的组名；顶层为 null。 */
    val scopeGroupName: String? = null,

    /** 用户组全量（长按菜单边界/范围回退参考；不做空组门控）。 */
    val userGroups: List<BookshelfGroupUiModel> = emptyList(),

    /** 筛选端口已注册（筛选入口可渲染）；false 时书架维持现状平铺。 */
    val filterSelectorAvailable: Boolean = false,

    /** 管理筛选器面板数据（全量系统筛选器，含隐藏；P4）。 */
    val manageableFilters: List<BookshelfGroupUiModel> = emptyList(),

    /** 归组面板目标书（null = 面板关闭）；P1。 */
    val assignBook: BookshelfItemUiModel? = null,
    /** 归组面板当前勾选集（用户组 id；乐观覆盖 + 回流对账）。 */
    val assignMembership: Set<Long> = emptySet(),
) {
    val isEmpty: Boolean get() = entries.isEmpty() && !isLoading
}

/** 范围 × 筛选双轴选择快照（uiState combine 第三支的五元合并载体）。 */
private data class ShelfSelection(
    val userGroups: List<BookshelfGroupUiModel>,
    val scope: Long,
    val filters: List<BookshelfGroupUiModel>,
    val filter: Long,
    val manageableFilters: List<BookshelfGroupUiModel>,
)

/**
 * 书架 ViewModel。
 *
 * 书架数据经 BookshelfEngine 端口获取（宿主 Room 流 + UiModel 映射），
 * 与刷新标志合并为单一 UiState 流。
 *
 * 加载态只出现在首次订阅前（[stateIn] 初始值）；重新订阅（如从阅读页
 * 返回）时 [stateIn] 保留着上次数据，Room 流重发同值不触发重组，
 * 不会重复闪加载页。
 *
 * 刷新（[refresh]）对齐宿主书架手动刷新（下拉刷新/菜单「更新目录」传
 * 当前分组书单的口径）：手动按当前选中分组，自动全量；并发拉取书架书
 * 目录，进度经引擎侧 sync 保留，失败标记 updateError；
 * 进入首页（ViewModel 创建）后延迟自动刷新一次。单本书的刷新管线
 * 在宿主 bridge（BookshelfEngineImpl.refreshBookToc）。
 */
class BookshelfViewModel(application: Application) : AndroidViewModel(application) {

    private val engine get() = EInkEngineRegistry.bookshelfEngine
    private val settings get() = EInkEngineRegistry.globalSettings

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    private val _updatingUrls = MutableStateFlow<Set<String>>(emptySet())

    // 布局随合并样式流派生（uiState.isGridLayout = style.isGridLayout），
    // 面板提交即时生效
    private val styleState = BookshelfStyleState(engine.style, viewModelScope)

    // 分组端口可选：未注册（旧宿主）时组流退化为空、选中恒「全部」，
    // 书架维持现状平铺（诚实退化，不伪造空分组）
    private val groupEngine get() = EInkEngineRegistry.bookshelfGroupEngine

    /** 筛选状态宿主（精选筛选器列表 + 选中乐观覆盖，复用 [BookshelfGroupState]）。 */
    internal val filterState = BookshelfGroupState(
        savedSelected = groupEngine?.selectedFilter
            ?: flowOf(BookshelfGroupIds.NONE),
        groupsFlow = groupEngine?.observeFilters()
            ?: flowOf(emptyList()),
        scope = viewModelScope,
        fallbackId = BookshelfGroupIds.NONE,
    )

    /** 范围状态宿主（用户组列表 + 选中乐观覆盖 + 失联回退全部）。 */
    internal val groupState = BookshelfGroupState(
        savedSelected = groupEngine?.selectedGroup
            ?: flowOf(BookshelfGroupIds.ALL),
        groupsFlow = groupEngine?.observeUserGroups()
            ?: flowOf(emptyList()),
        scope = viewModelScope,
    )

    /** 归组面板状态：目标书 + 成员集（宿主流 + 乐观覆盖，目标书切换即重订）。
     *  组列表不另起流，直接复用 [BookshelfGroupState.groups]（userGroups）。 */
    private val assignState = MutableStateFlow<BookshelfItemUiModel?>(null)
    private val assignOverride = MutableStateFlow<Set<Long>?>(null)

    /**
     * 宿主成员集真值快照：toggle/建组的覆盖种子回退源。不取
     * [uiState] 值——它经多重 combine + stateIn 传播，openAssign 切换
     * 目标书后到 Room 首次回流之间存在陈旧窗口（空/上一书真值），
     * 此时点首个勾选会把错误集合写成永久覆盖（回流真值永不追平）。
     * 本快照由 init 收集器距 Room 一跳同步维护；openAssign 切换目标书
     * 时同步清空，避免上一书真值成为下一书种子。
     */
    private val assignSavedLatest = MutableStateFlow<Set<Long>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val assignSavedFlow = assignState
        .flatMapLatest { book ->
            book?.let { groupEngine?.observeBookGroupMembership(it.bookUrl) } ?: flowOf(emptySet())
        }

    private val assignMembershipFlow =
        assignSavedFlow.combine(assignOverride) { saved, override -> override ?: saved }

    init {
        // 乐观覆盖追平清除：宿主回流追平覆盖值时清空覆盖，此后直通宿主流
        //（镜像 [BookshelfGroupState] 的追平清除）
        viewModelScope.launch {
            assignSavedFlow.collect { saved ->
                assignSavedLatest.value = saved
                if (assignOverride.value == saved) assignOverride.value = null
            }
        }
    }

    // 内容条目流：范围 × 筛选双轴驱动——组视图（scope != ALL）走
    // observeGroupBooks（筛选继承作用于组内容）；顶层有分组端口走
    // observeShelfWithGroups（组条目置顶 + 书籍的混合条目）；未注册
    // 分组端口的旧宿主诚实退化为 observeShelf 纯书籍。distinctUntilChanged
    // 防止选中流重发同值导致 Room 重订阅
    @OptIn(ExperimentalCoroutinesApi::class)
    private val entriesFlow = combine(
        groupState.selected,
        filterState.selected,
        // 分组展示支持（默认关）：关 = 组视图/文件夹视图不可达
        styleState.style.map { it.groupsEnabled }.distinctUntilChanged(),
    ) { scope, filter, groupsEnabled ->
        Triple(scope, filter, groupsEnabled)
    }.distinctUntilChanged().flatMapLatest { (scope, filter, groupsEnabled) ->
        // getter 端口快照（注册状态在流生命周期内不变；局部 val 同时
        // 解锁 when 分支内的智能转换）
        val groupEngine = groupEngine
        when {
            groupsEnabled && scope != BookshelfGroupIds.ALL ->
                (groupEngine?.observeGroupBooks(scope) ?: engine.observeShelf())
                    .map { list -> list.map { BookshelfShelfEntryUiModel.Book(it) } }
            // 双模式（spec 2026-10-16）：无筛选 = 文件夹视图（分组条目 +
            // 未归组书）；任何筛选（含全部）= 平铺视图（全量匹配书单）
            filter != BookshelfGroupIds.NONE && groupEngine != null ->
                groupEngine.observeFilteredShelf(filter)
                    .map { list -> list as List<BookshelfShelfEntryUiModel> }
            // 分组关闭（或防御性 NONE+关）：恒平铺——落「全部」
            !groupsEnabled && groupEngine != null ->
                groupEngine.observeFilteredShelf(BookshelfGroupIds.ALL)
                    .map { list -> list as List<BookshelfShelfEntryUiModel> }
            groupEngine != null -> groupEngine.observeShelfWithGroups()
            else -> engine.observeShelf()
                .map { list -> list.map { BookshelfShelfEntryUiModel.Book(it) } }
        }
    }

    // notShelf 行已在宿主查询内过滤，并由宿主物理删除；此处
    // 直接使用查询结果，与 View 版保持一致。
    // UiModel 映射放在宿主 bridge：只在 Room 发射（books 表变化）时执行
    // 一次，刷新期间 updatingUrls 频繁翻转时 combine 复用缓存的最新列表。
    // 样式（宿主快照 + 面板乐观覆盖）已在 [styleState] 归一为单一 flow，
    // 此处直接并入 UiState。
    val uiState: StateFlow<BookshelfUiState> =
        combine(
            combine(entriesFlow, _isRefreshing, _updatingUrls) { entries, refreshing, updating ->
                Triple(entries, refreshing, updating)
            },
            styleState.style,
            combine(
                groupState.groups, groupState.selected,
                filterState.groups, filterState.selected,
                groupEngine?.observeManageableFilters()
                    ?: flowOf(emptyList()),
            ) { userGroups, scope, filters, filter, manageable ->
                ShelfSelection(userGroups, scope, filters, filter, manageable)
            },
            assignState,
            assignMembershipFlow,
        ) { frame, style, selection, assignBook, membership ->
            val (frameEntries, refreshing, updatingUrls) = frame
            BookshelfUiState(
                entries = frameEntries,
                isLoading = false,
                isRefreshing = refreshing,
                updatingBookUrls = updatingUrls,
                isGridLayout = style.isGridLayout,
                style = style,
                filters = if (style.groupsEnabled) selection.filters
                else selection.filters.filterNot { it.groupId == BookshelfGroupIds.NONE },
                selectedFilterId = selection.filter,
                selectedScopeId = selection.scope,
                scopeGroupName = selection.userGroups
                    .firstOrNull { it.groupId == selection.scope }?.name,
                userGroups = selection.userGroups,
                filterSelectorAvailable = groupEngine != null,
                manageableFilters = selection.manageableFilters,
                assignBook = assignBook,
                assignMembership = membership,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookshelfUiState())

    /**
     * 提交书架样式（个性化配置面板写入口）：乐观置覆盖层，宿主落库由
     * [engine.setStyle] 异步完成，快照追平后覆盖层自动清除。
     */
    fun updateStyle(style: BookshelfStyle) {
        styleState.submit(style)
        viewModelScope.launch {
            try {
                engine.setStyle(style)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }

    /** 切换筛选（仅顶层入口可达；乐观置覆盖，筛选与范围=ALL 一并落库——
     *  选筛选即当前视图为平铺，冷启动按此恢复）。 */
    fun selectFilter(filterId: Long) {
        filterState.submit(filterId)
        viewModelScope.launch {
            try {
                groupEngine?.setSelectedFilter(filterId)
                groupEngine?.setSelectedGroup(BookshelfGroupIds.ALL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }

    /** 打开归组面板（长按书菜单「加入分组」）。 */
    fun openAssign(book: BookshelfItemUiModel) {
        assignState.value = book
        assignOverride.value = null
        assignSavedLatest.value = emptySet()
    }

    fun closeAssign() {
        assignState.value = null
        assignOverride.value = null
    }

    /** 当前生效成员集（覆盖优先，回退宿主真值快照）——toggle/建组的覆盖种子统一取全集。 */
    private fun currentAssignMembership(): Set<Long> =
        assignOverride.value ?: assignSavedLatest.value

    /** 勾/取消勾一个组：乐观置覆盖，宿主位掩码异步落库。 */
    fun toggleAssignMembership(groupId: Long, member: Boolean) {
        val book = assignState.value ?: return
        assignOverride.value = currentAssignMembership()
            .let { if (member) it + groupId else it - groupId }
        viewModelScope.launch {
            try {
                groupEngine?.setBookGroupMembership(book.bookUrl, groupId, member)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }

    /** 新建分组并立即勾选当前书（归组面板顶部行）。 */
    fun createGroupAndAssign(name: String) {
        val book = assignState.value ?: return
        viewModelScope.launch {
            try {
                val newId = groupEngine?.createGroup(name) ?: return@launch
                groupEngine?.setBookGroupMembership(book.bookUrl, newId, true)
                assignOverride.value = currentAssignMembership() + newId
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                _messages.emit(UserMessage.from(R.string.eink_group_name_exists))
            } catch (e: IllegalStateException) {
                _messages.emit(UserMessage.from(R.string.eink_group_limit_reached))
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }

    /** 进入组视图（聚合条目点击）。 */
    fun enterGroup(groupId: Long) {
        groupState.submit(groupId)
        viewModelScope.launch {
            try {
                groupEngine?.setSelectedGroup(groupId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }

    /** 退出组视图（点击顶栏分组名）：乐观置覆盖并落库 saveTabPosition=ALL——
     *  当前视图即记录，冷启动按退出后的状态恢复。 */
    fun exitGroup() {
        groupState.submit(BookshelfGroupIds.ALL)
        viewModelScope.launch {
            try {
                groupEngine?.setSelectedGroup(BookshelfGroupIds.ALL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }

    /** 长按条目管理：重命名（IAE=重名/空名，toast 反馈；Room 回流驱动 UI 更新）。 */
    fun renameGroup(groupId: Long, name: String) {
        viewModelScope.launch {
            try {
                groupEngine?.renameGroup(groupId, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                _messages.emit(UserMessage.from(R.string.eink_group_name_exists))
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }

    /** 排序面板退出：一次性提交用户组新序（面板本地已连续换位，此处整表落库）。 */
    fun applyGroupAdjustments(orderedGroupIds: List<Long>, shownGroupIds: Set<Long>) {
        viewModelScope.launch {
            try {
                groupEngine?.applyGroupAdjustments(orderedGroupIds, shownGroupIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }

    /**
     * 分组展示支持开关（样式面板首项）。过渡语义：开→默认选中「无筛选」
     * （文件夹视图）；关→若当前为无筛选则默认选中第一项筛选（平铺）。
     */
    fun setGroupsEnabled(enabled: Boolean) {
        val currentStyle = uiState.value.style
        styleState.submit(currentStyle.copy(groupsEnabled = enabled))
        viewModelScope.launch {
            try {
                engine.setStyle(currentStyle.copy(groupsEnabled = enabled))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
                return@launch
            }
            if (enabled) {
                selectFilter(BookshelfGroupIds.NONE)
            } else if (uiState.value.selectedFilterId == BookshelfGroupIds.NONE) {
                val first = uiState.value.filters
                    .firstOrNull { it.groupId != BookshelfGroupIds.NONE }?.groupId
                    ?: BookshelfGroupIds.ALL
                selectFilter(first)
            }
        }
    }

    /** 调整面板守卫提示：最后一项显示中的条目不可隐藏。 */
    fun notifyLastShownGuard() {
        viewModelScope.launch {
            _messages.emit(UserMessage.from(R.string.eink_group_keep_last_shown))
        }
    }

    /** 管理筛选器面板退出：一次性提交系统筛选器新序与显隐集。 */
    fun applyFilterAdjustments(orderedFilterIds: List<Long>, shownFilterIds: Set<Long>) {
        viewModelScope.launch {
            try {
                groupEngine?.applyFilterAdjustments(orderedFilterIds, shownFilterIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }

    /** 长按条目管理：解散（书籍保留仅清位；Room 回流移除组条目）。 */
    fun dissolveGroup(groupId: Long) {
        viewModelScope.launch {
            try {
                groupEngine?.dissolveGroup(groupId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }

    private var refreshJob: Job? = null

    // 刷新性能日志：与 View 版 MainViewModel 同 tag（ShelfBench），便于两版对拍与刷新慢的问题定位
    private val benchInFlight = AtomicInteger(0)
    private val benchOk = AtomicInteger(0)
    private val benchErr = AtomicInteger(0)
    private var benchPeak = 0

    private fun benchReset() = synchronized(this) {
        benchInFlight.set(0)
        benchOk.set(0)
        benchErr.set(0)
        benchPeak = 0
    }

    private fun benchTrackPeak(cur: Int) = synchronized(this) {
        if (cur > benchPeak) benchPeak = cur
    }

    init {
        // 与 View 版 MainViewModel.init 对齐：启动时物理删除未加书架
        //（notShelf）的隐藏行。详情页/搜索/阅读页的预取可能写入 notShelf 行，
        // 统一在此清理，后续查询与刷新无需再逐个过滤。
        viewModelScope.launch(Dispatchers.IO) {
            engine.deleteBooksNotInBookshelf()
        }
        // 与 View 版 MainActivity 自动刷新一致：仅在设置开启时，
        // 进入首页延迟 1 秒自动刷新一次；ViewModel 常驻，
        // 从阅读页/搜索返回不会重复触发。
        if (settings.autoRefreshBook) {
            viewModelScope.launch {
                delay(AUTO_REFRESH_DELAY_MS)
                refresh("auto")
            }
        }
        Log.i(
            TAG,
            "EInk VM init autoRefreshBook=${settings.autoRefreshBook} threadCount=${settings.threadCount}"
        )
    }

    /**
     * 刷新书架：进行中时直接忽略，与宿主刷新的排队去重行为一致——宿主
     * 下拉刷新和菜单「更新目录」都不会中断进行中的任务；E-Ink 逐书独立
     * 刷新无整轮队列，简化为活动期间 no-op。
     *
     * 刷新范围：手动触发按当前选中分组（对齐宿主传当前展示书单的口径，
     * 「全部」即全量）；自动触发恒全量（对齐宿主启动 upAllBookToc，
     * 避免冷启动停在某组时其余组的更新被跳过）。
     */
    fun refresh() = refresh("manual")

    fun refresh(trigger: String) {
        if (refreshJob?.isActive == true) {
            Log.i(TAG, "EInk refresh ignored (job active) trigger=$trigger")
            return
        }
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val myJob = coroutineContext[Job]
            val benchStart = SystemClock.elapsedRealtime()
            benchReset()
            _isRefreshing.value = true
            _updatingUrls.value = emptySet()
            try {
                // 手动刷新按 UI 当前生效的选中分组（含乐观覆盖），自动刷新
                // 恒全量（见函数 KDoc）
                val scopeGroupId =
                    if (trigger == "manual") groupState.selected.first() else BookshelfGroupIds.ALL
                val books = engine.updatableBooks(scopeGroupId)
                val concurrency = min(settings.threadCount, MAX_REFRESH_CONCURRENCY)
                Log.i(
                    TAG,
                    "EInk refresh start trigger=$trigger group=$scopeGroupId " +
                        "scope=${books.size} concurrency=$concurrency"
                )
                books.asFlow()
                    .onEachParallel(concurrency) { book ->
                        updateToc(book.bookUrl, books)
                    }
                    .collect()
            } finally {
                if (refreshJob === myJob) {
                    _isRefreshing.value = false
                    Log.i(
                        TAG,
                        "EInk refresh end elapsedMs=${SystemClock.elapsedRealtime() - benchStart} " +
                                "ok=${benchOk.get()} err=${benchErr.get()} peakInFlight=$benchPeak"
                    )
                    // 兜底触发预缓存泵：刷新被取消时，最后一批入队可能
                    // 落在逐书触发之后；泵幂等复用，重复触发无代价。泵在
                    // 进程级作用域运行，本 VM 随后销毁也不影响其继续消费。
                    startCacheBook()
                }
            }
        }
    }

    /**
     * 触发预缓存泵（消费逻辑对齐 View 版 `MainViewModel.cacheBook`）：
     * 消费宿主入队的章节，已缓存跳过、失败重试 3 次。
     *
     * 与 View 版的调度差异：不整轮刷新结束后才启动，也不在刷新期间
     * 暂停——原「目录优先」会让多书刷新的几分钟里一章不下，而目录刷新
     * 与章节下载并发额度分离（threadCount vs cacheBookThreadCount），
     * 且均为挂起式网络请求，可安全并行。泵随每本书入队触发（见
     * [updateToc]），此处亦为刷新结束/取消的兜底。View 版的
     * CacheBookService 前台服务判断省略——E-Ink 模式与完整模式互斥
     * （切换即 CLEAR_TASK），服务不会并行运行。
     */
    private fun startCacheBook() {
        if (settings.preDownloadChapterCount == 0) return
        CacheBookPump.start(engine)
    }

    /**
     * 更新一本书的目录（管线在宿主 bridge）：此处只保留
     * updating 标记与 ShelfBench 统计/日志。
     */
    private suspend fun updateToc(bookUrl: String, books: List<BookshelfItemUiModel>) {
        _updatingUrls.update { it + bookUrl }
        val benchStart = SystemClock.elapsedRealtime()
        val benchCur = benchInFlight.incrementAndGet()
        benchTrackPeak(benchCur)
        var benchResult = "noBook"
        val benchName = books.find { it.bookUrl == bookUrl }?.name ?: ""
        try {
            benchResult = when (engine.refreshBookToc(bookUrl)) {
                BookshelfTocRefreshResult.OK -> "ok"
                BookshelfTocRefreshResult.NO_BOOK -> "noBook"
                BookshelfTocRefreshResult.NO_SOURCE -> "noSource"
                BookshelfTocRefreshResult.ERROR -> "error"
            }
            if (benchResult == "ok") {
                // 该书预缓存已在宿主入队，立即触发泵消费，不等整轮刷新
                // 结束（泵幂等复用，逐书触发无重复代价）
                startCacheBook()
            }
        } finally {
            benchInFlight.decrementAndGet()
            if (benchResult == "ok") benchOk.incrementAndGet() else benchErr.incrementAndGet()
            Log.i(
                TAG,
                "EInk book done name=<$benchName> result=$benchResult " +
                        "inFlight=${benchInFlight.get()} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - benchStart} url=$bookUrl"
            )
            _updatingUrls.update { it - bookUrl }
        }
    }

    companion object {
        private const val TAG = "ShelfBench"

        /**
         * 目录刷新并发上限：取用户 threadCount 设置，不再压到
         * AppConst.MAX_THREAD（那是固定线程池的 CPU 侧尺寸上限，目录刷新
         * 的网络请求为挂起式、不受线程数约束）；仅以 OkHttp Dispatcher
         * 全局 maxRequests（默认 64）为防护上界，超过它请求只在 OkHttp
         * 内排队，无任何收益。
         */
        private const val MAX_REFRESH_CONCURRENCY = 64

        /** 进入首页自动刷新的延迟（毫秒），对齐 View 版节奏 */
        private const val AUTO_REFRESH_DELAY_MS = 1000L
    }
}
