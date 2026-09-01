package io.legado.app.eink.bridge

import android.content.Context
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.EInkKeyEventHub
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.eink.contract.EInkSettings
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * E-Ink 引擎桥接层装配入口。
 *
 * 宿主侧唯一职责：把 app 引擎能力以 [io.legado.app.eink.contract] 端口
 * 实现的形式提供给 :modules:eink。E-Ink 入口 Activity onCreate 中调用
 * [install]（必须早于任何 E-Ink Composable 组合）。
 *
 * 移植到新上游时：本目录（eink/bridge/）是唯一需要按目标引擎重写的
 * 部分，模块侧零改动（见 docs/eink-porting.md 的差异表）。
 */
object EInkBridge {

    fun install(context: Context) {
        EInkSettings.attach(context)
        EInkEngineRegistry.install(
            globalSettings = GlobalSettingsImpl,
            bookshelfEngine = BookshelfEngineImpl,
            searchEngine = SearchEngineImpl,
            tocEngine = TocEngineImpl,
            bookDetailEngine = BookDetailEngineImpl,
            changeSourceEngine = ChangeSourceEngineImpl,
            coverEngine = CoverEngineImpl,
            readerEngine = ReaderEngineImpl,
            keyEventHub = EInkKeyEventHub(),
        )
        // 封面开关为快照状态缓存：每次进入 E-Ink 与宿主设置快照对齐，
        // 防止完整模式（或上一会话）修改后的陈旧值
        CoverEngineImpl.syncFromGateway()
    }
}

// E-Ink 设置项的异步落盘作用域：端口契约保持同步 setter，写入经
// Gateway update（DataStore 原子提交）在本作用域承接；写后立即读
// getter 不保证可见新值，UI 侧应以本地状态做乐观更新。
// internal：供同目录拆分出的各端口实现文件（如 CoverEngineImpl）共用
internal val einkSettingsWriteScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * 全局设置视图。
 *
 * threadCount/preDownloadNum 走宿主 @Deprecated 同步门面
 * （"settings" DataStore 的 AppConfigStore 内存快照，主线程零 IO），
 * 无 Gateway 等价物；fontScaleSetting 走 AppConfigStore 同步快照
 * （attach 期配置，写入后 recreate 入口 Activity 才生效）；
 * autoRefreshBook/defaultToRead（「我的」页可写）经 OtherSettingsGateway、
 * volumeKeyPage 经 ReadSettingsGateway、changeSourceCheckAuthor 经
 * ChangeSourceSettingsGateway 读写。
 */
private object GlobalSettingsImpl : GlobalSettings, KoinComponent {

    private val changeSourceSettingsGateway:
        io.legado.app.domain.gateway.ChangeSourceSettingsGateway by inject()

    private val otherSettingsGateway: OtherSettingsGateway by inject()

    private val readSettingsGateway: ReadSettingsGateway by inject()

    override val threadCount: Int get() = AppConfig.threadCount

    override var fontScaleSetting: Int?
        get() = AppConfigStore.getInt(PreferKey.fontScale)
        set(value) {
            if (value == null) {
                AppConfigStore.remove(PreferKey.fontScale)
            } else {
                AppConfigStore.putInt(PreferKey.fontScale, value)
            }
        }

    override var autoRefreshBook: Boolean
        get() = otherSettingsGateway.currentSettings.autoRefresh
        set(value) {
            einkSettingsWriteScope.launch {
                otherSettingsGateway.update { it.copy(autoRefresh = value) }
            }
        }

    override var defaultToRead: Boolean
        get() = otherSettingsGateway.currentSettings.defaultToRead
        set(value) {
            einkSettingsWriteScope.launch {
                otherSettingsGateway.update { it.copy(defaultToRead = value) }
            }
        }

    override var volumeKeyPage: Boolean
        get() = readSettingsGateway.currentSettings.volumeKeyPage
        set(value) {
            einkSettingsWriteScope.launch {
                readSettingsGateway.update { it.copy(volumeKeyPage = value) }
            }
        }

    override val preDownloadNum: Int get() = AppConfig.preDownloadNum
    override val changeSourceCheckAuthor: Boolean
        get() = changeSourceSettingsGateway.currentSettings.checkAuthor

    override val useAntiAlias: Boolean
        get() = otherSettingsGateway.currentSettings.antiAlias
}
