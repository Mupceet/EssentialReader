package io.legado.app.eink.contract

import kotlinx.coroutines.flow.Flow

/**
 * 一次多书源搜索的会话句柄。
 *
 * 生命周期：模块在进入搜索时 [SearchEngine.createSearchSession]，会话
 * 期间可多次 [search]（新搜索应中断上一次进行中的源搜索），离开搜索页
 * 时 [close]。[close] 后宿主不得再向回调发事件。
 */
interface SearchSession {

    /**
     * 发起多源搜索。
     *
     * @param searchId 模块侧单调递增的会话内序号：传入宿主会话用于过滤
     *   过期结果——新搜索发起后，旧搜索仍在途的源结果按宿主机制丢弃
     *   （无内建序号过滤的宿主至少要保证 [cancelSearch] 语义）。
     *   回调本身**不携带** searchId，事件归因由模块状态机完成。
     * @param query 搜索词。
     */
    fun search(searchId: Long, query: String)

    /** 取消进行中的搜索（已发出的结果保留，回调收到 [SearchSessionCallback.onSearchCancel]）。 */
    fun cancelSearch()

    /** 结束会话，释放宿主侧资源。 */
    fun close()
}

/**
 * 搜索事件回调。
 *
 * 事件顺序约定：
 * ```text
 * search(searchId, query)
 *  └─ onSearchStart
 *      ├─ onSearchProgress(已完成源, 总源数) × N   顶部进度提示
 *      ├─ onSearchSuccess(批次结果) × N ─► VM 合并 + 去重 + 排序
 *      └─ onSearchFinish(空?, 有下一页?)
 * cancelSearch ─► onSearchCancel(cause?)
 * ```
 *
 * 宿主实现义务：把多源搜索的中间/最终结果转发到回调（事件须归属到
 * 正确的 searchId）。回调线程不限定，模块侧自行切主线程。
 */
interface SearchSessionCallback {
    /** 搜索开始（全部源尚未发起）。 */
    fun onSearchStart()

    /**
     * 书源维度进展（已完成源数 / 参与源总数），结果页顶部进度提示用。
     * 宿主引擎无源粒度事件时，可用「已返回结果的源数 / 启用源总数」
     * 近似——无结果的源不计入，进度会偏低后在 finish 跳满；**不可省略
     * 不调**（进度提示将停留在初始态，属可感知的功能缺失）。
     */
    fun onSearchProgress(processedSources: Int, totalSources: Int)

    /** 一批搜索结果到达（按源粒度增量推送，模块自行合并去重排序）。 */
    fun onSearchSuccess(books: List<SearchBookUiModel>)

    /**
     * 全部源搜索结束。
     *
     * @param isEmpty 本次搜索无任何结果。
     * @param hasMore true = 存在分页（模块可发起下一页搜索）。
     */
    fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)

    /** 搜索被取消（[exception] 为取消原因，无则 null）。 */
    fun onSearchCancel(exception: Throwable?)
}

/**
 * 搜索端口：搜索历史管理与多书源搜索会话。
 *
 * 搜索页流程：
 * ```text
 * 进入搜索页
 *  ├─ observeSearchHistory() ─► 历史 chip 流（最近使用倒序）
 *  └─ observeBookshelfMatchKeys() ─► 结果「已在书架」标记
 * 输入完成 ─► recordSearchQuery(query) ─► 历史流发射新序
 * 发起搜索 ─► createSearchSession(callback)
 *              └─ session.search(searchId, query)
 *                   └─ 回调事件时序见 [SearchSessionCallback]
 * 再次搜索 ─► session.search(新 searchId, 新 query)（旧源搜索被中断）
 * 离开页面 ─► session.close()
 * ```
 *
 * 职责边界：模块搜索页 VM 保留输入状态机、结果合并去重排序与分页
 * 编排；宿主实现负责书源搜索执行、搜索范围解析与历史存取。
 */
interface SearchEngine {

    /**
     * 书架匹配键集合流：宿主把书架书籍投影为「name-author / name /
     * bookUrl」三种形态的键，模块用它标记搜索结果的「已在书架」。
     * 书架增删后流应发射新集合。
     */
    fun observeBookshelfMatchKeys(): Flow<Set<String>>

    /** 搜索历史流（按最近使用时间倒序）。 */
    fun observeSearchHistory(): Flow<List<SearchHistoryUiModel>>

    /** 记录一次搜索词（已存在则使用次数 +1 并置顶；异步落盘即可）。 */
    suspend fun recordSearchQuery(query: String)

    /** 删除单条搜索历史。 */
    suspend fun removeSearchHistory(word: String)

    /** 清空搜索历史。 */
    suspend fun clearSearchHistory()

    /** 创建搜索会话（模块持有其生命周期，离开搜索页时调 [SearchSession.close]）。 */
    fun createSearchSession(callback: SearchSessionCallback): SearchSession
}
