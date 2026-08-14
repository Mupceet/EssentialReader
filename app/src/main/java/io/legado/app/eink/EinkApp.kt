package io.legado.app.eink

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.legado.app.eink.navigation.EinkScreen
import io.legado.app.eink.navigation.EinkNavController

/**
 * E-Ink 应用根 Composable。
 *
 * 负责三件事:
 * 1. 包裹 [EInkTheme]（全局禁用涟漪、灰度调色板、14sp 最小字体）
 * 2. 提供刷新控制器 CompositionLocal
 * 3. 通过 when 分支直接替换屏幕内容（无动画过渡，规范 §12, §44）
 *
 * UDF: 导航状态由 [EinkNavController] 管理，屏幕内部状态由各自 ViewModel 管理。
 */
@Composable
fun EinkApp(
    controller: EinkNavController = EinkNavController.remember()
) {
    EInkThemeWrapper {
        when (val screen = controller.screen) {
            is EinkScreen.Bookshelf -> {
                // TODO Phase 2: BookshelfScreen(controller)
                PlaceholderScreen("书架（待实现）")
            }

            is EinkScreen.Reader -> {
                // TODO Phase 2: ReaderScreen(screen.bookUrl, controller)
                PlaceholderScreen("阅读器（待实现）")
            }

            is EinkScreen.Toc -> {
                // TODO Phase 2: TocScreen(screen.bookUrl, controller)
                PlaceholderScreen("目录（待实现）")
            }

            is EinkScreen.Search -> {
                // TODO Phase 3: SearchScreen(controller)
                PlaceholderScreen("搜索（待实现）")
            }

            is EinkScreen.BookSource -> {
                // TODO Phase 3: BookSourceScreen(controller)
                PlaceholderScreen("书源管理（待实现）")
            }

            is EinkScreen.ReaderSettings -> {
                // TODO Phase 2: ReaderSettingsScreen(screen.bookUrl, controller)
                PlaceholderScreen("阅读设置（待实现）")
            }
        }
    }
}

/**
 * 主题包裹层。
 *
 * 引用 :modules:eink 中的 EInkTheme，该主题在根节点通过
 * LocalIndication provides NoIndication 全局禁用涟漪效果。
 */
@Composable
private fun EInkThemeWrapper(content: @Composable () -> Unit) {
    io.legado.app.eink.theme.EInkTheme {
        content()
    }
}

@Composable
private fun PlaceholderScreen(text: String) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = text)
        }
    }
}
