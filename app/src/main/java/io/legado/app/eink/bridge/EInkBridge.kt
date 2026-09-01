package io.legado.app.eink.bridge

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import io.legado.app.constant.PreferKey
import io.legado.app.domain.gateway.CoverSettingsGateway
import io.legado.app.domain.gateway.DownloadCacheSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.EInkKeyEventHub
import io.legado.app.eink.contract.EInkSettings
import io.legado.app.eink.contract.GlobalSettings
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
 * 移植到新上游时：本目录（eink/bridge/）是唯一需要重写的部分，模块侧
 * 零改动（见 docs/eink-porting.md 的差异表）。
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
        GlobalSettingsImpl.syncUseDefaultCover()
    }
}

// E-Ink 设置项的异步落盘作用域：端口契约保持同步 setter，写入经
// Gateway update（DataStore 原子提交）在本作用域承接；写后立即读
// getter 不保证可见新值，UI 侧应以本地状态做乐观更新（useDefaultCover
// 的快照状态除外——见 GlobalSettingsImpl）。
internal val einkSettingsWriteScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * 全局设置视图。
 *
 * 设置项全部经设置网关读写：threadCount/preDownloadNum 经
 * DownloadCacheSettingsGateway（与旧 AppConfig 门面同键同默认值的
 * 快照）；autoRefreshBook/defaultToRead（「我的」页可写）经
 * OtherSettingsGateway、volumeKeyPage 经 ReadSettingsGateway、
 * changeSourceCheckAuthor 经 ChangeSourceSettingsGateway；
 * useDefaultCover（「我的」页可写）为本对象持有的 Compose 快照状态 +
 * CoverSettingsGateway 异步落盘——组合内读取订阅变化，切换后开关行与
 * 书架/详情可见封面立即重组。
 *
 * fontScaleSetting 例外地仍走 AppConfigStore 同步快照（护栏允许——
 * 禁的是 AppConfig/ui.config.*Config）：端口契约的 null = 未设置/跟随
 * 系统缩放语义在域模型中不存在（AppShellSettings.fontScale 非空，仓库
 * 把未设置映射为 10），且写入需支持 remove 键；域化须先把该字段
 * nullable 化并处理完整模式读方与主题导出，另行专项。
 */
private object GlobalSettingsImpl : GlobalSettings, KoinComponent {

    private val changeSourceSettingsGateway:
        io.legado.app.domain.gateway.ChangeSourceSettingsGateway by inject()

    private val otherSettingsGateway: OtherSettingsGateway by inject()

    private val readSettingsGateway: ReadSettingsGateway by inject()

    private val downloadCacheSettingsGateway: DownloadCacheSettingsGateway by inject()

    private val coverSettingsGateway: CoverSettingsGateway by inject()

    /** 封面开关快照状态（install 时与宿主设置对齐，防跨模式往返陈旧值）。 */
    private val useDefaultCoverState = mutableStateOf(false)

    fun syncUseDefaultCover() {
        useDefaultCoverState.value = coverSettingsGateway.currentSettings.useDefaultCover
    }

    override val threadCount: Int
        get() = downloadCacheSettingsGateway.currentSettings.threadCount

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

    override var useDefaultCover: Boolean
        get() = useDefaultCoverState.value
        set(value) {
            // 同步更新快照状态（组合即时可见），落盘异步承接
            useDefaultCoverState.value = value
            einkSettingsWriteScope.launch {
                coverSettingsGateway.update { it.copy(useDefaultCover = value) }
            }
        }

    override val preDownloadNum: Int
        get() = downloadCacheSettingsGateway.currentSettings.preDownloadNum

    override val changeSourceCheckAuthor: Boolean
        get() = changeSourceSettingsGateway.currentSettings.checkAuthor

    override val useAntiAlias: Boolean
        get() = otherSettingsGateway.currentSettings.antiAlias
}
