package io.legado.app.eink.bookshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.eink.arch.UserMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 书架屏幕 UiState（扁平布尔标志位，参考 JBusDriver UDF 模式）。
 */
data class BookshelfUiState(
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = books.isEmpty() && !isLoading
}

/**
 * 书架 ViewModel。
 *
 * 直接复用 [appDb.bookDao.flowByGroup] 获取书架数据，
 * 无需数据层复制。使用 [BookGroup.IdAll] 显示所有书架书。
 *
 * 加载态只出现在首次订阅前（[stateIn] 初始值）；重新订阅（如从阅读页
 * 返回）时 [stateIn] 保留着上次数据，Room 流重发同值不触发重组，
 * 不会重复闪加载页。
 */
class BookshelfViewModel(application: Application) : AndroidViewModel(application) {

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    val uiState: StateFlow<BookshelfUiState> =
        appDb.bookDao.flowByGroup(BookGroup.IdAll)
            .map { books -> BookshelfUiState(books = books, isLoading = false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookshelfUiState())
}
