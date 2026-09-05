package io.legado.app.eink.bridge

import androidx.compose.runtime.mutableStateOf
import io.legado.app.constant.PreferKey
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import splitties.init.appCtx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt

/** [GlobalSettingsImpl.keepScreenOn] 的历史键（EInkSettings 时期逐字继承）。 */
private const val KEY_READER_KEEP_SCREEN_ON = "einkReaderKeepScreenOn"

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
        )
        // 封面开关为快照状态缓存：每次进入 E-Ink 与宿主设置对齐，
        // 防止完整模式（或上一会话）修改后的陈旧值
        GlobalSettingsImpl.syncUseDefaultCover()
    }
}

// E-Ink 设置项的异步落盘作用域（本宿主 putPref 为同步 SP.apply，作用域
// 仅保留 fire-and-forget 形状以对齐端口契约的写入语义档位）。
internal val einkSettingsWriteScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /**
     * E-Ink 自有偏好（历史键落默认 SP 文件）；同步读写，读取在阅读 VM
     * 构造时一次、写入即时生效。
     */
    override var keepScreenOn: Boolean
        get() = appCtx.getPrefBoolean(KEY_READER_KEEP_SCREEN_ON, false)
        set(value) {
            appCtx.putPrefBoolean(KEY_READER_KEEP_SCREEN_ON, value)
        }

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
}
