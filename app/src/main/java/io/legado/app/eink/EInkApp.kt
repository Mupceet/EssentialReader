package io.legado.app.eink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import io.legado.app.eink.component.EInkText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.legado.app.eink.bookdetail.BookDetailRoute
import io.legado.app.eink.booksource.BookSourceRoute
import io.legado.app.eink.home.HomeRoute
import io.legado.app.eink.navigation.EInkNavController
import io.legado.app.eink.navigation.EInkScreen
import io.legado.app.eink.search.SearchRoute
import io.legado.app.eink.settings.SettingsRoute
import io.legado.app.eink.toc.TocRoute

/**
 * E-Ink 应用根 Composable。
 *
 * 1. 通过 when 分支直接替换屏幕内容（无动画过渡，规范 §12, §44）
 * 2. 导航状态由 [EInkNavController] 管理（UDF: state hoisted to controller）
 * 3. 用 [rememberSaveableStateHolder] 按"导航栈条目"保留 rememberSaveable 状态
 */
@Composable
fun EInkApp(
    controller: EInkNavController = EInkNavController.remember()
) {
    // 单 Activity 架构：系统返回键优先 pop 导航栈，根页面时交还系统（退出应用）
    BackHandler(enabled = controller.canPop) {
        controller.pop()
    }

    // when 分支切换会整体卸载离屏内容，rememberSaveable 状态随之丢失；
    // 用 SaveableStateHolder 按"导航栈条目"（entryId）保留：
    // pop 返回复用同一 entryId（保留状态），重新 push 则为新 entryId（即首次进入）。
    val stateHolder = rememberSaveableStateHolder()
    val screen = controller.screen
    val entryId = controller.currentEntryId

    // 按当前栈条目提供 ViewModelStoreOwner：ViewModel 作用域随条目隔离，
    // 退出界面再进入时 ViewModel 全新（如搜索历史/结果不再残留）。
    val viewModelStoreOwner = remember(controller) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore
                get() = controller.currentViewModelStore
        }
    }

    stateHolder.SaveableStateProvider(key = entryId) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
            when (screen) {
                is EInkScreen.Home -> {
                    HomeRoute(
                        onBookClick = { bookUrl ->
                            // 阅读界面接入前，点击书籍进入目录
                            controller.navigate(EInkScreen.Toc(bookUrl))
                        },
                        onSearch = { controller.navigate(EInkScreen.Search) },
                        onBookSource = { controller.navigate(EInkScreen.BookSource) },
                        onSettings = { controller.navigate(EInkScreen.Settings) },
                    )
                }

                is EInkScreen.Search -> {
                    SearchRoute(
                        onBack = { controller.pop() },
                        onBookClick = { book ->
                            controller.navigate(
                                EInkScreen.BookDetail(book.name, book.author, book.bookUrl)
                            )
                        }
                    )
                }

                is EInkScreen.BookDetail -> {
                    BookDetailRoute(
                        name = screen.name,
                        author = screen.author,
                        bookUrl = screen.bookUrl,
                        onBack = { controller.pop() },
                        onOpenToc = { bookUrl -> controller.navigate(EInkScreen.Toc(bookUrl)) },
                        onRead = { bookUrl -> controller.navigate(EInkScreen.Reader(bookUrl)) },
                    )
                }

                is EInkScreen.BookSource -> {
                    BookSourceRoute(onBack = { controller.pop() })
                }

                is EInkScreen.Settings -> {
                    SettingsRoute(onBack = { controller.pop() })
                }

                is EInkScreen.Toc -> {
                    TocRoute(
                        bookUrl = screen.bookUrl,
                        onBack = { controller.pop() }
                    )
                }

                is EInkScreen.Reader -> {
                    PlaceholderScreen("阅读器（待接入 ReadBook 引擎）")
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        EInkText(text = text)
    }
}