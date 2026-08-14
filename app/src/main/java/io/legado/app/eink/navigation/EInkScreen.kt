package io.legado.app.eink.navigation

/**
 * E-Ink 屏幕路由定义。
 *
 * 遵循 E-Ink Design System 规范 §13: 页面切换采用 immediate replacement，
 * 使用 sealed interface + when 分支实现离散状态导航（无动画过渡）。
 */
sealed interface EInkScreen {

    /** 首页（书架/我的 双 Tab，顶部搜索框 + 底部通用操作栏） */
    data object Home : EInkScreen

    data object Search : EInkScreen

    data object BookSource : EInkScreen

    data object Settings : EInkScreen

    /** 目录（阅读界面接入前作为书籍详情入口） */
    data class Toc(val bookUrl: String) : EInkScreen

    /** 阅读器（Phase 后续接入 ReadBook 引擎） */
    data class Reader(val bookUrl: String) : EInkScreen
}
