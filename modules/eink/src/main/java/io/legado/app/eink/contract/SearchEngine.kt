package io.legado.app.eink.contract

import kotlinx.coroutines.flow.Flow

/** 搜索会话（桥接宿主 SearchModel，回调式 → 端口回调）。 */
interface SearchSession {
    fun search(searchId: Long, key: String)
    fun cancelSearch()
    fun close()
}

/** 搜索回调（宿主 SearchModel.CallBack 的模块侧投影）。 */
interface SearchSessionCallback {
    fun onSearchStart()
    fun onSearchSuccess(books: List<SearchBookUiModel>)
    fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
    fun onSearchCancel(exception: Throwable?)
}

/**
 * 搜索端口。
 *
 * 上游差异（如 legadoM-Ink 的 CallBack 多 onSearchProgress/
 * onSourceStatesChanged、搜索范围 SearchScope 语义）全部由桥接层吸收。
 */
interface SearchEngine {

    /** 书架匹配键集合流（name-author / name / bookUrl 三键，判断“已在书架”）。 */
    fun observeBookshelfKeys(): Flow<Set<String>>

    /** 搜索历史流（按使用频次排序）。 */
    fun observeSearchHistory(): Flow<List<SearchHistoryUiModel>>

    /** 记录一次搜索词（已存在则 usage+1 并更新时间）。 */
    suspend fun recordSearchKey(key: String)

    /** 删除单条搜索历史。 */
    suspend fun removeSearchHistory(word: String)

    /** 清空搜索历史。 */
    suspend fun clearSearchHistory()

    /** 创建搜索会话（作用域由 VM 持有，onCleared 时调 [SearchSession.close]）。 */
    fun createSearchSession(callback: SearchSessionCallback): SearchSession
}
