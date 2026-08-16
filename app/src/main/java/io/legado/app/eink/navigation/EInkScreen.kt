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

    /** 书籍详情（搜索结果等入口） */
    data class BookDetail(val name: String, val author: String, val bookUrl: String) : EInkScreen

    /**
     * 目录。
     *
     * @param fromReader 是否自阅读页进入：选章后复用下方既有阅读页（仅弹出目录）；
     * 否则选章后替换栈顶进入阅读页（返回回到目录的上一级，如详情页）
     */
    data class Toc(val bookUrl: String, val fromReader: Boolean = false) : EInkScreen

    /** 阅读器（复用 View 版 ReadBook/ChapterProvider 渲染引擎） */
    data class Reader(val bookUrl: String) : EInkScreen

    /** 换源（跨书源搜索并切换当前书籍来源） */
    data class ChangeSource(val bookUrl: String) : EInkScreen
}
