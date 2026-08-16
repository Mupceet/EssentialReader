package io.legado.app.eink

import android.app.Application
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.legado.app.eink.bookdetail.BookDetailRoute
import io.legado.app.eink.changesource.ChangeSourceRoute
import io.legado.app.eink.home.HomeRoute
import io.legado.app.eink.navigation.EInkNavController
import io.legado.app.eink.navigation.EInkScreen
import io.legado.app.eink.reader.ReaderRoute
import io.legado.app.eink.search.SearchRoute
import io.legado.app.eink.toc.TocRoute
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.startActivity

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
    val context = LocalContext.current
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
            if (screen is EInkScreen.Reader) {
                // 阅读界面自管系统栏避让（见 ReaderScreen）：页眉紧贴状态栏下方，
                // 后续支持收起状态栏时，该区域即页眉区域，正文始终从页眉之下开始
                ReaderRoute(
                    bookUrl = screen.bookUrl,
                    onBack = { controller.pop() },
                    onOpenToc = { bookUrl ->
                        controller.navigate(EInkScreen.Toc(bookUrl, fromReader = true))
                    },
                    onChangeSource = { bookUrl ->
                        controller.navigate(EInkScreen.ChangeSource(bookUrl))
                    },
                )
            } else {
                // 其余界面统一避让系统栏（Edge-to-Edge 下系统栏透明覆盖在背景上）
                Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    when (screen) {
                is EInkScreen.Home -> {
                    HomeRoute(
                        onBookClick = { bookUrl ->
                            // 书架点击直接进入阅读
                            controller.navigate(EInkScreen.Reader(bookUrl))
                        },
                        onSearch = { controller.navigate(EInkScreen.Search) },
                        onOpenFullMode = {
                            // 完整模式（View UI）：恢复原主题，墨水屏界面退出；
                            // 导入导出等管理功能在完整模式中完成，
                            // 再次启用需在完整模式中选择纯净阅读(墨水屏)主题
                            AppConfig.exitEInkPureMode()
                            context.startActivity<MainActivity> {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        },
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
                                onOpenToc = { bookUrl ->
                                    controller.navigate(EInkScreen.Toc(bookUrl, fromReader = false))
                                },
                                onRead = { bookUrl -> controller.navigate(EInkScreen.Reader(bookUrl)) },
                            )
                        }

                        is EInkScreen.Toc -> {
                            TocRoute(
                                bookUrl = screen.bookUrl,
                                onBack = { controller.pop() },
                                onOpenReader = { bookUrl ->
                                    if (screen.fromReader) {
                                        // 复用下方既有阅读页：弹出目录即可，
                                        // 阅读页重新挂载时按新保存的进度跳章
                                        controller.pop()
                                    } else {
                                        // 详情页等路径：目录出栈、阅读页入栈，返回回到详情页
                                        controller.replaceTop(EInkScreen.Reader(bookUrl))
                                    }
                                }
                            )
                        }

                        is EInkScreen.ChangeSource -> {
                            ChangeSourceRoute(
                                bookUrl = screen.bookUrl,
                                onBack = { controller.pop() },
                            )
                        }

                        is EInkScreen.Reader -> Unit // 上方已处理
                    }
                }
            }
        }
    }
}