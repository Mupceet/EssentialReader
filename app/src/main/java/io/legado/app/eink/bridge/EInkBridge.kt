package io.legado.app.eink.bridge

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import io.legado.app.constant.PreferKey
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.eink.contract.ReaderTapZoneGrid
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import splitties.init.appCtx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt

/**
 * E-Ink 自有偏好的专属 prefs 文件（模块 0.5.0 契约口径）：pullDownBookmark
 * 与 readerTapZones 落这里而非宿主默认 prefs 文件——模块侧约定自有键
 * 不与完整模式共享存储，且默认文件有被整体迁移/清空的历史风险。
 */
private const val EINK_PREFS_FILE = "eink_preferences"

/** 下拉添加书签（E-Ink 自有偏好，默认关）。 */
private const val KEY_PULL_DOWN_BOOKMARK = "einkReaderPullDownBookmark"

/** 点击区域九宫格整键（9 位编码，[ReaderTapZoneGrid.encode]）。 */
private const val KEY_READER_TAP_ZONES = "einkReaderTapZones"

/** 本宿主完整模式无界面字体缩放设置：E-Ink 自管键（0 = 未设置，跟随系统）。 */
private const val KEY_FONT_SCALE = "fontScale"

/**
 * E-Ink 引擎桥接层装配入口（本宿主 = legado-with-MD3 旧栈，无设置网关/
 * Koin，全部经 AppConfig / ReadBookConfig / SharedPreferences 扩展读写）。
 *
 * 由入口子类 EInkMainActivity 的 onInstallEngines 钩子调用（模块模板基类
 * EInkHostActivity 在 attachBaseContext 首行触发，早于任何 E-Ink
 * Composable 组合与端口读取）。
 */
object EInkBridge {

    /**
     * E-Ink tip 行高（dp）：模块页眉/页脚按「可用高度 ×0.7」推导字号，
     * 可用高度恒为行高值（带 = tip 内边距 + 行高，内边距在带内扣减）
     * → 字号恒 ≈12sp，对齐完整模式 PageView 的 12sp tip 行。
     */
    internal const val TIP_ROW_DP = 17

    /** 模块页脚顶部的自动翻页进度条高度（dp，带宽预算内扣除）。 */
    internal const val TIP_PROGRESS_BAR_DP = 2

    fun install() {
        EInkEngineRegistry.install(
            globalSettings = GlobalSettingsImpl,
            bookshelfEngine = BookshelfEngineImpl,
            searchEngine = SearchEngineImpl,
            tocEngine = TocEngineImpl,
            bookDetailEngine = BookDetailEngineImpl,
            changeSourceEngine = ChangeSourceEngineImpl,
            coverEngine = CoverEngineImpl,
            readerEngine = ReaderEngineImpl,
            appUpdateEngine = AppUpdateEngineImpl,
            selectionEngine = ReaderSelectionEngineImpl,
            marksEngine = MarksEngineImpl,
            bookshelfGroupEngine = BookshelfGroupEngineImpl,
        )
        // 封面开关为快照状态缓存：每次进入 E-Ink 与宿主设置对齐，
        // 防止完整模式（或上一会话）修改后的陈旧值
        GlobalSettingsImpl.syncUseDefaultCover()
        // tip 带预留按当前可见性同步（引擎排版避让页眉/页脚带）
        ReaderEngineImpl.syncTipReserves()
    }

    /**
     * 清零引擎侧 E-Ink 装饰预留（回完整模式前/入口销毁时调用——
     * ChapterProvider 为进程级单例，残留预留会使完整模式排版让位）。
     */
    fun resetEngineDecorations() {
        ChapterProvider.einkHeaderReserveDp = 0
        ChapterProvider.einkFooterReserveDp = 0
    }
}

// E-Ink 设置项的异步落盘作用域（本宿主 putPref 为同步 SP.apply，作用域
// 仅保留 fire-and-forget 形状以对齐端口契约的写入语义档位）。
internal val einkSettingsWriteScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private fun einkSharedPreferences() =
    appCtx.getSharedPreferences(EINK_PREFS_FILE, Context.MODE_PRIVATE)

/** E-Ink 自有偏好读取（专属 prefs 文件，SP 同步语义）。 */
internal fun einkPrefBoolean(key: String, default: Boolean): Boolean =
    einkSharedPreferences().getBoolean(key, default)

internal fun einkPrefInt(key: String, default: Int): Int =
    einkSharedPreferences().getInt(key, default)

/** E-Ink 自有偏好写入（SP.apply 异步落盘，内存即时可见）。 */
internal fun einkPrefPutBoolean(key: String, value: Boolean) {
    einkSharedPreferences().edit().putBoolean(key, value).apply()
}

internal fun einkPrefPutInt(key: String, value: Int) {
    einkSharedPreferences().edit().putInt(key, value).apply()
}

/**
 * 全局设置视图（本宿主实现：AppConfig/ReadBookConfig/SP 扩展）。
 *
 * 写入语义：putPrefBoolean/putPrefInt 均为 SP.apply 异步落盘——写后立即
 * 读 getter 不保证可见新值，UI 侧按 fire-and-forget 档做乐观更新
 * （useDefaultCover 的快照状态除外）。
 */
private object GlobalSettingsImpl : GlobalSettings {

    /** 封面开关快照状态（install 时与宿主设置对齐，防跨模式往返陈旧值）。 */
    private val useDefaultCoverState = mutableStateOf(false)

    fun syncUseDefaultCover() {
        useDefaultCoverState.value = AppConfig.useDefaultCover
    }

    override val threadCount: Int
        get() = AppConfig.threadCount

    override var autoRefreshBook: Boolean
        get() = AppConfig.autoRefreshBook
        set(value) {
            appCtx.putPrefBoolean(PreferKey.autoRefresh, value)
        }

    override var defaultToRead: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.defaultToRead)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.defaultToRead, value)
        }

    /** 音量键翻页（实时档：按键时逐次读 SP，无一致性窗口）。 */
    override var volumeKeyPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPage, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.volumeKeyPage, value)
        }

    /** 快照状态档：组合内读取订阅变化，切换后开关行与封面立即重组。 */
    override var useDefaultCover: Boolean
        get() = useDefaultCoverState.value
        set(value) {
            useDefaultCoverState.value = value
            einkSettingsWriteScope.launch {
                appCtx.putPrefBoolean(PreferKey.useDefaultCover, value)
                AppConfig.useDefaultCover = value
            }
        }

    /**
     * 下拉添加书签（E-Ink 自有偏好，默认关）：专属 prefs 文件同步读写，
     * 读取在阅读 VM 构造时一次、写入即时生效且同步落盘。
     */
    override var pullDownBookmark: Boolean
        get() = einkSharedPreferences().getBoolean(KEY_PULL_DOWN_BOOKMARK, false)
        set(value) {
            einkSharedPreferences().edit().putBoolean(KEY_PULL_DOWN_BOOKMARK, value).apply()
        }

    /**
     * 阅读页隐藏系统状态栏（转发宿主阅读设置 hideStatusBar，与完整模式
     * 同键共享存储）。宿主该键为启动期一次性装载的内存快照——写入时
     * 双写内存值与 prefs，页眉可见性实时对齐。
     */
    override var hideStatusBar: Boolean
        get() = ReadBookConfig.hideStatusBar
        set(value) {
            ReadBookConfig.hideStatusBar = value
            appCtx.putPrefBoolean(PreferKey.hideStatusBar, value)
        }

    /**
     * 段评气泡：宿主不支持，恒 false（写丢弃）。
     *
     * 宿主的 enableReview 为 DEBUG 构建专属实验特性（release 恒关），且为
     * 半成品：评论数硬编码（ReviewColumn count=100）、无段评数据管线、
     * 引擎靠向标题/段落尾部注入 reviewChar「▨」再按行尾条件转自绘评论钮
     * ——与契约的段评模型（带 click 脚本的图片槽位）不同构，转发会把
     * 注入字符漏进页快照文本块（真机表现为标题与段尾的小方块）。
     *
     * 已知待办（契约改进提案，2026-09-18）：段评气泡应由宿主声明能力
     * （如 GlobalSettings 能力位或独立端口），模块据此隐藏「显示段评
     * 气泡」开关及相关元素——当前该行无条件渲染，不支持宿主上呈现为
     * 恒关死开关。
     */
    override var showReviewBubbles: Boolean
        get() = false
        set(value) {}

    /**
     * 点击区域九宫格（E-Ink 自有偏好，不转发完整模式 clickAction* 键）：
     * 整键 9 位编码存专属 prefs 文件，脏值由模块解码回落默认分区。
     */
    override var readerTapZones: ReaderTapZoneGrid
        get() = ReaderTapZoneGrid.decodeOrDefault(
            einkSharedPreferences().getString(KEY_READER_TAP_ZONES, null)
        )
        set(value) {
            einkSharedPreferences().edit()
                .putString(KEY_READER_TAP_ZONES, value.encode()).apply()
        }

    override val useAntiAlias: Boolean
        get() = AppConfig.useAntiAlias

    override val preDownloadChapterCount: Int
        get() = AppConfig.preDownloadNum

    override val changeSourceCheckAuthor: Boolean
        get() = AppConfig.changeSourceCheckAuthor

    /**
     * 应用内字体缩放（attach 期档）：本宿主完整模式无对应设置，E-Ink 自管
     * 键；0 视为未设置（null 语义——0/10 不在 0.8~1.6 区间，行为等价）。
     */
    override var fontScaleSetting: Int?
        get() = appCtx.getPrefInt(KEY_FONT_SCALE).takeIf { it != 0 }
        set(value) {
            appCtx.putPrefInt(KEY_FONT_SCALE, value ?: 0)
        }

    /**
     * 云端进度同步总开关（转发宿主「同步阅读进度」键，与完整模式「备份与
     * 恢复」共享存储）。E-Ink 一键全开语义由 [ReaderProgressSyncer] 落地：
     * 主开关开即完整双向同步（Plus 子键仅完整模式生效）。
     */
    override var syncReadingProgress: Boolean
        get() = AppConfig.syncBookProgress
        set(value) {
            appCtx.putPrefBoolean(PreferKey.syncBookProgress, value)
        }
}
