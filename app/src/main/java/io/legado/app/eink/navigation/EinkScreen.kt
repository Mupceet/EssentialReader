package io.legado.app.eink.navigation

/**
 * E-Ink 屏幕路由定义。
 *
 * 遵循 E-Ink Design System 规范 §13: 页面切换采用 immediate replacement，
 * 使用 sealed interface + when 分支实现离散状态导航（无动画过渡）。
 */
sealed interface EinkScreen {

    data object Bookshelf : EinkScreen

    data class Reader(val bookUrl: String) : EinkScreen

    data class Toc(val bookUrl: String) : EinkScreen

    data object Search : EinkScreen

    data object BookSource : EinkScreen

    data class ReaderSettings(val bookUrl: String) : EinkScreen
}
