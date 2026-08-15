package io.legado.app.eink

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.legado.app.eink.bookdetail.BookDetailRoute
import io.legado.app.eink.booksource.BookSourceRoute
import io.legado.app.eink.changesource.ChangeSourceRoute
import io.legado.app.eink.home.HomeRoute
import io.legado.app.eink.navigation.EInkNavController
import io.legado.app.eink.navigation.EInkScreen
import io.legado.app.eink.reader.ReaderRoute
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
    // 实现 HasDefaultViewModelProviderFactory 以便 AndroidViewModel(application)
    // 等构造方式仍可正常创建。
    val app = LocalContext.current.applicationContext as Application
    val viewModelStoreOwner = remember(controller, app) {
        object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore
                get() = controller.currentViewModelStore

            override val defaultViewModelProviderFactory: ViewModelProvider.Factory
                get() = ViewModelProvider.AndroidViewModelFactory.getInstance(app)

            override val defaultViewModelCreationExtras: CreationExtras
                get() = MutableCreationExtras().apply {
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = app
                }
        }
    }

    stateHolder.SaveableStateProvider(key = entryId) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
            when (screen) {
                is EInkScreen.Home -> {
                    HomeRoute(
                        onBookClick = { bookUrl ->
                            // 书架点击直接进入阅读
                            controller.navigate(EInkScreen.Reader(bookUrl))
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
                        onBack = { controller.pop() },
                        onOpenReader = { bookUrl -> controller.navigate(EInkScreen.Reader(bookUrl)) }
                    )
                }

                is EInkScreen.Reader -> {
                    ReaderRoute(
                        bookUrl = screen.bookUrl,
                        onBack = { controller.pop() },
                        onOpenToc = { bookUrl -> controller.navigate(EInkScreen.Toc(bookUrl)) },
                        onChangeSource = { bookUrl ->
                            controller.navigate(EInkScreen.ChangeSource(bookUrl))
                        },
                    )
                }

                is EInkScreen.ChangeSource -> {
                    ChangeSourceRoute(
                        bookUrl = screen.bookUrl,
                        onBack = { controller.pop() },
                    )
                }
            }
        }
    }
}