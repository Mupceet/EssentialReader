package io.legado.app.eink.engine

/**
 * E-Ink 引擎端口注册表（service locator）。
 *
 * :modules:eink 内的全部 ViewModel 经此获取宿主提供的引擎能力端口，
 * 模块本身不依赖任何引擎类型 —— 这是「模块可整体嵌入任意 legado 系
 * 上游」的关键。宿主（app 模块的 `eink/bridge/`）在 E-Ink 入口
 * Activity 创建时调用 [install] 注册全部实现。
 *
 * 不选择逐 ViewModel 注入 Factory 的原因：E-Ink 导航控制器
 * （EInkNavController）使用默认 AndroidViewModelFactory 构造 VM，
 * 逐屏改注入管道侵入性大于收益；注册表在单 Activity 架构下由
 * 入口 Activity 生命周期兜底（VM 组合必然晚于 Activity.onCreate）。
 *
 * 移植到新上游时：模块树原样复制，仅重写宿主侧 bridge/ 下的实现并
 * 在入口处调用 [install]（见 docs/eink-porting.md）。
 */
object EinkEngineRegistry {

    lateinit var globalSettings: GlobalSettings
        private set
    lateinit var bookshelfEngine: BookshelfEngine
        private set
    lateinit var searchEngine: SearchEngine
        private set
    lateinit var tocEngine: TocEngine
        private set
    lateinit var bookDetailEngine: BookDetailEngine
        private set
    lateinit var changeSourceEngine: ChangeSourceEngine
        private set
    lateinit var coverEngine: CoverEngine
        private set

    @Volatile
    var installed: Boolean = false
        private set

    /**
     * 注册全部引擎端口实现。宿主 E-Ink 入口 Activity.onCreate 中调用，
     * 必须早于任何 E-Ink Composable 组合（VM 构造）。
     */
    fun install(
        globalSettings: GlobalSettings,
        bookshelfEngine: BookshelfEngine,
        searchEngine: SearchEngine,
        tocEngine: TocEngine,
        bookDetailEngine: BookDetailEngine,
        changeSourceEngine: ChangeSourceEngine,
        coverEngine: CoverEngine,
    ) {
        this.globalSettings = globalSettings
        this.bookshelfEngine = bookshelfEngine
        this.searchEngine = searchEngine
        this.tocEngine = tocEngine
        this.bookDetailEngine = bookDetailEngine
        this.changeSourceEngine = changeSourceEngine
        this.coverEngine = coverEngine
        installed = true
    }
}
