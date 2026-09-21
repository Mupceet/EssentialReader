package io.legado.app.eink.bridge

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import io.legado.app.constant.PreferKey
import io.legado.app.domain.gateway.CoverSettingsGateway
import io.legado.app.domain.gateway.DownloadCacheSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import splitties.init.appCtx

/**
 * E-InK 自有偏好（readerTapZonesEncoding/pullDownBookmark）的存储位。
 *
 * 历史存储位是宿主默认 prefs 文件（`<packageName>_preferences`），而该
 * 文件正是 DataStore「settings」的 MIGRATE_ALL_KEYS 迁移源
 * （SettingsRepository）：androidx 在每次进程启动时对非空源文件执行
 * 迁移并在 cleanUp 中 clear() 整文件——自有键写进去后进程一重启即被
 * 清空回默认（点击区域/常亮开关反复重置的根因）。故迁至专属文件
 * [FILE_NAME]，键名逐字保留；旧默认文件中的残留由该迁移机制自行清空。
 */
internal object EinkLegacyPrefsStore {

    /** E-InK 自有偏好的专属 prefs 文件（独立于 DataStore 迁移源）。 */
    const val FILE_NAME = "eink_preferences"

    /** readerTapZonesEncoding 的自有键（9 位编码整键，格式见模块侧）。 */
    const val KEY_TAP_ZONES = "einkReaderTapZones"

    /** pullDownBookmark 的自有键（默认关）。 */
    const val KEY_PULL_DOWN_BOOKMARK = "einkReaderPullDownBookmark"

    /** 最近文件字体历史的自有键（换行分隔 path 列表，格式见模块侧）。 */
    const val KEY_RECENT_FONTS = "einkRecentFontPaths"

    fun prefs(): SharedPreferences =
        appCtx.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}

/**
 * E-Ink 引擎桥接层装配入口。
 *
 * 宿主侧唯一职责：把 app 引擎能力以 [io.legado.app.eink.contract] 端口
 * 实现的形式提供给 :modules:eink。由入口子类 EInkMainActivity 的
 * onInstallEngines 钩子调用（模块模板基类 EInkHostActivity 在
 * attachBaseContext 首行触发，早于任何 E-Ink Composable 组合与端口读取）。
 *
 * 移植到新上游时：本目录（eink/bridge/）是唯一需要重写的部分，模块侧
 * 零改动（差异记录见 contract/EINK-PORTING.md §3）。
 */
object EInkBridge {

    /**
     * 当前在前的 E-Ink 宿主 Activity（EinkMainActivity onStart/onStop 挂载）。
     * 端口实现弹出宿主 UI 层（段评半屏 WebView 等 DialogFragment）的事务
     * 宿主与生命周期作用域来源；无前台活动时（退后台/已销毁）相关分派
     * 静默放弃。
     */
    private var hostActivity: androidx.appcompat.app.AppCompatActivity? = null

    fun attachHostActivity(activity: androidx.appcompat.app.AppCompatActivity) {
        hostActivity = activity
    }

    fun detachHostActivity(activity: androidx.appcompat.app.AppCompatActivity) {
        if (hostActivity === activity) hostActivity = null
    }

    fun hostActivity(): androidx.appcompat.app.AppCompatActivity? = hostActivity

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
            marksEngine = MarksEngineImpl,
            bookshelfGroupEngine = BookshelfGroupEngineImpl,
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
 * OtherSettingsGateway、volumeKeyPage、hideStatusBar 与
 * showReviewBubbles（后三者为阅读界面其它设置开关、转发宿主阅读设置
 * 键，与完整模式共享同一存储）经 ReadSettingsGateway、
 * changeSourceCheckAuthor 经 ChangeSourceSettingsGateway；
 * useDefaultCover（「我的」页可写）为本对象持有的 Compose 快照状态 +
 * CoverSettingsGateway 异步落盘——组合内读取订阅变化，切换后开关行与
 * 书架/详情可见封面立即重组；readerTapZonesEncoding、pullDownBookmark
 * （均为阅读菜单设置、E-InK 自有偏好、完整模式无对应设置；
 * readerTapZonesEncoding 不转发完整模式 clickAction* 键：值域只有三动作，
 * 转发会让两侧配置互相覆盖）同走
 * EinkLegacyPrefsStore 专属 prefs 文件不经设置网关（默认 prefs 文件是
 * DataStore 迁移源、启动即被整文件清空，不可作存储位）：readerTapZonesEncoding
 * （9 位编码整键原样存取，编码语义在模块侧）、recentFontPathsEncoding
 * （换行分隔 path 列表原样存取，编码语义在模块侧）与 pullDownBookmark
 * （下拉添加书签，默认关）为自有键；syncReadingProgress（「我的」页
 * 可写）经 BackupSettingsGateway 转发宿主「同步阅读进度」主键，写时
 * 带宿主设置页同款父子联动。
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

    private val backupSettingsGateway:
            io.legado.app.domain.gateway.BackupSettingsGateway by inject()

    /** 封面开关快照状态（install 时与宿主设置对齐，防跨模式往返陈旧值）。 */
    private val useDefaultCoverState = mutableStateOf(false)

    fun syncUseDefaultCover() {
        useDefaultCoverState.value = coverSettingsGateway.currentSettings.useDefaultCover
    }

    /** E-InK 自有偏好的存储位（[EinkLegacyPrefsStore] 专属文件，历史键名不变）。 */
    private val einkLegacyPrefs by lazy { EinkLegacyPrefsStore.prefs() }

    override var pullDownBookmark: Boolean
        get() = einkLegacyPrefs.getBoolean(EinkLegacyPrefsStore.KEY_PULL_DOWN_BOOKMARK, false)
        set(value) {
            einkLegacyPrefs.edit()
                .putBoolean(EinkLegacyPrefsStore.KEY_PULL_DOWN_BOOKMARK, value).apply()
        }

    override var readerTapZonesEncoding: String?
        get() = einkLegacyPrefs.getString(EinkLegacyPrefsStore.KEY_TAP_ZONES, null)
        set(value) {
            einkLegacyPrefs.edit()
                .putString(EinkLegacyPrefsStore.KEY_TAP_ZONES, value).apply()
        }

    override var recentFontPathsEncoding: String
        get() = einkLegacyPrefs.getString(EinkLegacyPrefsStore.KEY_RECENT_FONTS, "") ?: ""
        set(value) {
            einkLegacyPrefs.edit()
                .putString(EinkLegacyPrefsStore.KEY_RECENT_FONTS, value).apply()
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

    override var hideStatusBar: Boolean
        get() = readSettingsGateway.currentSettings.hideStatusBar
        set(value) {
            einkSettingsWriteScope.launch {
                readSettingsGateway.update { it.copy(hideStatusBar = value) }
            }
        }

    override var showReviewBubbles: Boolean
        get() = readSettingsGateway.currentSettings.showReviewBubbles
        set(value) {
            einkSettingsWriteScope.launch {
                readSettingsGateway.update { it.copy(showReviewBubbles = value) }
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

    override val preDownloadChapterCount: Int
        get() = downloadCacheSettingsGateway.currentSettings.preDownloadNum

    override val changeSourceCheckAuthor: Boolean
        get() = changeSourceSettingsGateway.currentSettings.checkAuthor

    override val useAntiAlias: Boolean
        get() = otherSettingsGateway.currentSettings.antiAlias

    override var syncReadingProgress: Boolean
        get() = backupSettingsGateway.currentSettings.syncBookProgress
        set(value) {
            einkSettingsWriteScope.launch {
                backupSettingsGateway.update {
                    it.copy(
                        syncBookProgress = value,
                        // 宿主设置页同款父子联动（BackupConfigViewModel）：
                        // 主开关关闭时「同步增强」子键一并关闭
                        syncBookProgressPlus = it.syncBookProgressPlus && value,
                    )
                }
            }
        }
}
