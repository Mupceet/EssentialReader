package io.legado.app.eink.booksource

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 书源管理 UiState。
 */
data class BookSourceUiState(
    val sources: List<BookSourcePart> = emptyList(),
    val searchKey: String = "",
    val isLoading: Boolean = true,
    val enabledCount: Int = 0,
) {

    val totalCount: Int get() = sources.size

    val isEmpty: Boolean
        get() = !isLoading && sources.isEmpty()
}

/**
 * 书源管理 ViewModel。
 *
 * 复用 [appDb.bookSourceDao.flowSearch]（响应式过滤）与
 * [appDb.bookSourceDao.enable]（启用开关）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookSourceViewModel(application: Application) : AndroidViewModel(application) {

    private val searchKey = MutableStateFlow("")

    private val _uiState = MutableStateFlow(BookSourceUiState())
    val uiState: StateFlow<BookSourceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                searchKey.flatMapLatest { key ->
                    if (key.isBlank()) {
                        appDb.bookSourceDao.flowAll()
                    } else {
                        appDb.bookSourceDao.flowSearch(key)
                    }
                },
                appDb.bookSourceDao.flowEnabled()
            ) { filtered, enabled ->
                BookSourceUiState(
                    sources = filtered,
                    searchKey = searchKey.value,
                    isLoading = false,
                    enabledCount = enabled.size
                )
            }.collect { _uiState.value = it }
        }
    }

    /** 搜索过滤书源 */
    fun search(key: String) {
        searchKey.value = key
    }

    /** 切换单个书源启用状态 */
    fun toggleSource(source: BookSourcePart) {
        viewModelScope.launch {
            appDb.bookSourceDao.enable(source.bookSourceUrl, !source.enabled)
        }
    }

    /** 全部启用/禁用（基于当前列表） */
    fun enableAll(enable: Boolean) {
        viewModelScope.launch {
            appDb.bookSourceDao.enable(enable, _uiState.value.sources)
        }
    }
}
