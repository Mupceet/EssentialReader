package io.legado.app.eink.contract

import io.legado.app.eink.app.EInkKeyEventHub
import io.legado.app.eink.contract.EInkEngineRegistry.install
import io.legado.app.eink.contract.EInkEngineRegistry.keyEventHub

/**
 * E-Ink 引擎端口注册表（service locator）。
 *
 * :modules:eink 内的全部 ViewModel 经此获取宿主提供的引擎能力端口，
 * 模块本身不依赖任何引擎类型 —— 这是「模块可整体嵌入任意 legado 系
 * 上游」的关键。宿主（app 模块的 `eink/bridge/`）经模块入口模板
 * [EInkHostActivity] 的 onInstallEngines 钩子
 * 调用 [install] 注册全部实现（attachBaseContext 首行，早于任何
 * E-Ink Composable 组合 / VM 构造 / 端口读取）。
 *
 * 不选择逐 ViewModel 注入 Factory 的原因：E-Ink 导航控制器
 * （EInkNavController）使用默认 AndroidViewModelFactory 构造 VM，
 * 逐屏改注入管道侵入性大于收益；注册表在单 Activity 架构下由
 * 入口模板生命周期兜底（VM 组合必然晚于 Activity onCreate）。
 *
 * 失败模式：端口未注册即被访问时，getter 抛出的
 * IllegalStateException 会指名缺失的端口与修复入口 —— 而非 lateinit
 * 的裸字段崩溃栈。install 允许重复调用（同进程内入口 Activity 重入，
 * 静态注册表仍在），语义为整体替换（last-wins）；每次进入 E-Ink
 * 重新装配同时承担宿主设置快照对齐（[keyEventHub] 亦随之重置），
 * 装配模板见 app 侧 EInkBridge.install。
 *
 * 移植到新上游时：模块树原样复制，仅重写宿主侧 bridge/ 下的端口实现
 * 并在入口子类的 onInstallEngines 钩子中调用 [install]
 * （见 docs/eink-porting.md）。
 */
object EInkEngineRegistry {

    private var _globalSettings: GlobalSettings? = null
    private var _bookshelfEngine: BookshelfEngine? = null
    private var _searchEngine: SearchEngine? = null
    private var _tocEngine: TocEngine? = null
    private var _bookDetailEngine: BookDetailEngine? = null
    private var _changeSourceEngine: ChangeSourceEngine? = null
    private var _coverEngine: CoverEngine? = null
    private var _readerEngine: ReaderEngine? = null

    /** 模块自有的按键枢纽（非宿主端口）：每次 install 重置，丢弃陈旧 handler。 */
    private var _keyEventHub = EInkKeyEventHub()

    val globalSettings: GlobalSettings
        get() = require(_globalSettings, "GlobalSettings")

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

    val keyEventHub: EInkKeyEventHub
        get() = _keyEventHub

    /**
     * 注册全部引擎端口实现。由模块入口模板 [EInkHostActivity]
     * 在 attachBaseContext（宿主 onInstallEngines 钩子）调用，必须早于任何
     * E-Ink Composable 组合（VM 构造）。重复调用为整体替换。
     */
    fun install(
        globalSettings: GlobalSettings,
        bookshelfEngine: BookshelfEngine,
        searchEngine: SearchEngine,
        tocEngine: TocEngine,
        bookDetailEngine: BookDetailEngine,
        changeSourceEngine: ChangeSourceEngine,
        coverEngine: CoverEngine,
        readerEngine: ReaderEngine,
    ) {
        _globalSettings = globalSettings
        _bookshelfEngine = bookshelfEngine
        _searchEngine = searchEngine
        _tocEngine = tocEngine
        _bookDetailEngine = bookDetailEngine
        _changeSourceEngine = changeSourceEngine
        _coverEngine = coverEngine
        _readerEngine = readerEngine
        _keyEventHub = EInkKeyEventHub()
    }

    /** 未初始化端口的统一报错（指名端口 + 修复入口）。 */
    private fun <T : Any> require(value: T?, port: String): T =
        checkNotNull(value) {
            ":modules:eink 引擎端口未注册：$port。宿主入口需先调用 EInkEngineRegistry.install(...) 完成装配（见 docs/eink-porting.md）"
        }
}
