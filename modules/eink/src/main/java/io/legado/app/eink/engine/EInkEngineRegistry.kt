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
 * 失败模式：端口未注册即被访问时，getter 抛出的
 * IllegalStateException 会指名缺失的端口与修复入口 —— 而非 lateinit
 * 的裸字段崩溃栈。install 允许重复调用（同进程内入口 Activity 重入，
 * 静态注册表仍在），语义为整体替换（last-wins）；正常宿主只在入口
 * onCreate 调用一次，装配模板见 app 侧 EInkBridge.install（含
 * EInkSettings.attach 的折叠，宿主无需单独初始化模块偏好）。
 *
 * 移植到新上游时：模块树原样复制，仅重写宿主侧 bridge/ 下的实现并
 * 在入口处调用 [install]（见 docs/eink-porting.md）。
 */
object EInkEngineRegistry {

    private var _globalSettings: GlobalSettings? = null
    private var _uiSettings: UiSettings? = null
    private var _bookshelfEngine: BookshelfEngine? = null
    private var _searchEngine: SearchEngine? = null
    private var _tocEngine: TocEngine? = null
    private var _bookDetailEngine: BookDetailEngine? = null
    private var _changeSourceEngine: ChangeSourceEngine? = null
    private var _coverEngine: CoverEngine? = null
    private var _readerEngine: ReaderEngine? = null

    val globalSettings: GlobalSettings
        get() = require(_globalSettings, "GlobalSettings")

    val uiSettings: UiSettings
        get() = require(_uiSettings, "UiSettings")

    val bookshelfEngine: BookshelfEngine
        get() = require(_bookshelfEngine, "BookshelfEngine")

    val searchEngine: SearchEngine
        get() = require(_searchEngine, "SearchEngine")

    val tocEngine: TocEngine
        get() = require(_tocEngine, "TocEngine")

    val bookDetailEngine: BookDetailEngine
        get() = require(_bookDetailEngine, "BookDetailEngine")

    val changeSourceEngine: ChangeSourceEngine
        get() = require(_changeSourceEngine, "ChangeSourceEngine")

    val coverEngine: CoverEngine
        get() = require(_coverEngine, "CoverEngine")

    val readerEngine: ReaderEngine
        get() = require(_readerEngine, "ReaderEngine")

    /**
     * 注册全部引擎端口实现。宿主 E-Ink 入口 Activity.onCreate 中调用，
     * 必须早于任何 E-Ink Composable 组合（VM 构造）。重复调用为整体替换。
     */
    fun install(
        globalSettings: GlobalSettings,
        uiSettings: UiSettings,
        bookshelfEngine: BookshelfEngine,
        searchEngine: SearchEngine,
        tocEngine: TocEngine,
        bookDetailEngine: BookDetailEngine,
        changeSourceEngine: ChangeSourceEngine,
        coverEngine: CoverEngine,
        readerEngine: ReaderEngine,
    ) {
        _globalSettings = globalSettings
        _uiSettings = uiSettings
        _bookshelfEngine = bookshelfEngine
        _searchEngine = searchEngine
        _tocEngine = tocEngine
        _bookDetailEngine = bookDetailEngine
        _changeSourceEngine = changeSourceEngine
        _coverEngine = coverEngine
        _readerEngine = readerEngine
    }

    /** 未初始化端口的统一报错（指名端口 + 修复入口）。 */
    private fun <T : Any> require(value: T?, port: String): T =
        checkNotNull(value) {
            ":modules:eink 引擎端口未注册：$port。宿主入口需先调用 EInkEngineRegistry.install(...) 完成装配（见 docs/eink-porting.md）"
        }
}
