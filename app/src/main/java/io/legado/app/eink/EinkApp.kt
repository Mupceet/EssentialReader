package io.legado.app.eink

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import io.legado.app.eink.bookshelf.BookshelfRoute
import io.legado.app.eink.booksource.BookSourceRoute
import io.legado.app.eink.navigation.EinkNavController
import io.legado.app.eink.navigation.EinkScreen
import io.legado.app.eink.search.SearchRoute
import io.legado.app.eink.settings.SettingsRoute
import io.legado.app.eink.toc.TocRoute

/**
 * E-Ink 应用根 Composable。
 *
 * 1. 通过 when 分支直接替换屏幕内容（无动画过渡，规范 §12, §44）
 * 2. 导航状态由 [EinkNavController] 管理（UDF: state hoisted to controller）
 */
@Composable
fun EinkApp(
    controller: EinkNavController = EinkNavController.remember()
) {
    // 单 Activity 架构：系统返回键优先 pop 导航栈，根页面时交还系统（退出应用）
    BackHandler(enabled = controller.canPop) {
        controller.pop()
    }

    when (val screen = controller.screen) {
        is EinkScreen.Bookshelf -> {
            BookshelfRoute(
                onBookClick = { bookUrl ->
                    // 阅读界面接入前，点击书籍进入目录
                    controller.navigate(EinkScreen.Toc(bookUrl))
                },
                onSearch = { controller.navigate(EinkScreen.Search) },
                onBookSource = { controller.navigate(EinkScreen.BookSource) },
                onSettings = { controller.navigate(EinkScreen.Settings) },
            )
        }

        is EinkScreen.Search -> {
            SearchRoute(onBack = { controller.pop() })
        }

        is EinkScreen.BookSource -> {
            BookSourceRoute(onBack = { controller.pop() })
        }

        is EinkScreen.Settings -> {
            SettingsRoute(onBack = { controller.pop() })
        }

        is EinkScreen.Toc -> {
            TocRoute(
                bookUrl = screen.bookUrl,
                onBack = { controller.pop() }
            )
        }

        is EinkScreen.Reader -> {
            PlaceholderScreen("阅读器（待接入 ReadBook 引擎）")
        }
    }
}

@Composable
private fun PlaceholderScreen(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text)
    }
}
