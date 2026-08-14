package io.legado.app.eink.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * E-Ink 导航控制器。
 *
 * 管理屏幕栈，页面切换通过直接替换状态完成
 * （规范 §12, §44: 页面 transition 统一为 NONE，无动画过渡）。
 */
class EinkNavController internal constructor(
    initial: EinkScreen,
) {
    private var backStack: MutableList<EinkScreen> = mutableListOf(initial)
    private var currentScreen by mutableStateOf(initial)

    val screen: EinkScreen
        get() = currentScreen

    val canPop: Boolean
        get() = backStack.size > 1

    fun navigate(screen: EinkScreen) {
        backStack.add(screen)
        currentScreen = screen
    }

    fun navigateAndClear(screen: EinkScreen) {
        backStack.clear()
        backStack.add(screen)
        currentScreen = screen
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        currentScreen = backStack.last()
        return true
    }

    companion object {
        /**
         * 创建并记住一个 [EinkNavController]。
         */
        @Composable
        fun remember(initial: EinkScreen = EinkScreen.Bookshelf): EinkNavController =
            remember(initial) { EinkNavController(initial) }
    }
}
