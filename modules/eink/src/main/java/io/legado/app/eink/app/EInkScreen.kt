package io.legado.app.eink.app

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

    /**
     * 书籍详情。
     *
     * @param fromReader 是否自阅读页进入：点"阅读"仅弹出详情（复用下方既有
     * 阅读页，详情不留在返回栈）；否则点"阅读"新进阅读页（详情保留在栈中，
     * 返回阅读页时可回到详情）
     */
    data class BookDetail(
        val name: String,
        val author: String,
        val bookUrl: String,
        val fromReader: Boolean = false,
    ) : EInkScreen

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

    /** 排版样式调试（逐级展示 EInkTheme.typography） */
    data object ThemeDebug : EInkScreen

    /** 组件预览（Design System Gallery，规范 §72：不依赖产品界面的组件验证面） */
    data object ComponentGallery : EInkScreen

    /** 字体大小设置（示例文字预览 + 抬手生效的倍率滑条，入口在「我的」页） */
    data object FontScaleSettings : EInkScreen

    /**
     * 字体设置（字体文件夹内文件字体分页列表选择，入口在「我的」页与
     * 阅读器字体配置弹层）。
     *
     * 两入口写径与生效流程不同，按 [fromReader] 路由分流（对齐完整模式
     * 两条字体流程）：
     * - 自阅读页进入：阅读字体（正文直选、标题/页眉跟随正文的统一写径），
     *   点选应用后即返回阅读页（对齐原浮层点选即关的路径）；
     * - 「我的」进入：应用界面字体（完整模式「外观 → 字体」同流程同键），
     *   选完停留本页，界面字体经设置流实时生效，底栏可清除回落默认。
     */
    data class FontSettings(val fromReader: Boolean = false) : EInkScreen
}
