package io.legado.app.eink.navigation

/**
 * E-Ink 屏幕路由定义。
 *
 * 遵循 E-Ink Design System 规范 §13: 页面切换采用 immediate replacement，
 * 使用 sealed interface + when 分支实现离散状态导航（无动画过渡）。
 */
sealed interface EinkScreen {

    data object Bookshelf : EinkScreen

    data object Search : EinkScreen

    data object BookSource : EinkScreen

    data object Settings : EinkScreen

    /** 目录（阅读界面接入前作为书籍详情入口） */
    data class Toc(val bookUrl: String) : EinkScreen

    /** 阅读器（Phase 后续接入 ReadBook 引擎） */
    data class Reader(val bookUrl: String) : EinkScreen
}
