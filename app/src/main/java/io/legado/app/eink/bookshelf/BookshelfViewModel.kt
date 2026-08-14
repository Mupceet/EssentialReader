package io.legado.app.eink.bookshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.eink.arch.UserMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
class BookshelfViewModel(application: Application) : AndroidViewModel(application) {

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UserMessage> = _messages

    val uiState: StateFlow<BookshelfUiState> =
        appDb.bookDao.flowByGroup(BookGroup.IdAll)
            .let { flow ->
                kotlinx.coroutines.flow.flow {
                    emit(BookshelfUiState(isLoading = true))
                    flow.collect { books ->
                        emit(BookshelfUiState(books = books, isLoading = false))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookshelfUiState())
}
