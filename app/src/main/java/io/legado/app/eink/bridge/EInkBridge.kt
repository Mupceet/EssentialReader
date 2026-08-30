package io.legado.app.eink.bridge

import android.content.Context
import coil3.request.ImageRequest
import io.legado.app.eink.engine.CoverEngine
import io.legado.app.eink.engine.EInkEngineRegistry
import io.legado.app.eink.engine.GlobalSettings
import io.legado.app.eink.engine.UiSettings
import io.legado.app.eink.settings.EInkSettings
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.coil.CoverExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * E-Ink 引擎桥接层装配入口。
 *
 * 宿主侧唯一职责：把 app 引擎能力以 [io.legado.app.eink.engine] 端口
 * 实现的形式提供给 :modules:eink。E-Ink 入口 Activity onCreate 中调用
 * [install]（必须早于任何 E-Ink Composable 组合）。
 *
 * 移植到新上游时：本目录（eink/bridge/）是唯一需要按目标引擎重写的
 * 部分，模块侧零改动（见 docs/eink-porting.md 的差异表）。
 */
object EInkBridge : KoinComponent {

    private val otherSettingsGateway: OtherSettingsGateway by inject()

    /**
     * 图片绘制抗锯齿开关（OtherSettings）。Compose 文件按本仓架构护栏
     * 禁止导入兼容 Config，画布经此处取值。
     */
    val useAntiAlias: Boolean
        get() = otherSettingsGateway.currentSettings.antiAlias

    fun install(context: Context) {
        EInkSettings.attach(context)
        EInkEngineRegistry.install(
            globalSettings = GlobalSettingsImpl,
            uiSettings = UiSettingsImpl,
            bookshelfEngine = BookshelfEngineImpl,
            searchEngine = SearchEngineImpl,
            tocEngine = TocEngineImpl,
            bookDetailEngine = BookDetailEngineImpl,
            changeSourceEngine = ChangeSourceEngineImpl,
            coverEngine = CoverEngineImpl,
            readerEngine = ReaderEngineImpl,
        )
    }
}

/**
 * 全局设置视图。
 *
 * threadCount/preDownloadNum 走宿主 @Deprecated 同步门面
 * （"settings" DataStore 的 AppConfigStore 内存快照，主线程零 IO），
 * 无 Gateway 等价物；autoRefreshBook/defaultToRead（「我的」页可写）
 * 与 changeSourceCheckAuthor 经 Koin 的设置 Gateway 读写。
 */
private object GlobalSettingsImpl : GlobalSettings, KoinComponent {

    private val changeSourceSettingsGateway:
        io.legado.app.domain.gateway.ChangeSourceSettingsGateway by inject()

    private val otherSettingsGateway: OtherSettingsGateway by inject()

    // 写入为 suspend（Gateway update 经 DataStore 原子提交），端口契约
    // 保持同步 setter，异步落盘由本作用域承接；写后立即读 getter 不保证
    // 可见新值，UI 侧应以本地状态做乐观更新
    private val writeScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val threadCount: Int get() = AppConfig.threadCount

    override var autoRefreshBook: Boolean
        get() = otherSettingsGateway.currentSettings.autoRefresh
        set(value) {
            writeScope.launch {
                otherSettingsGateway.update { it.copy(autoRefresh = value) }
            }
        }

    override var defaultToRead: Boolean
        get() = otherSettingsGateway.currentSettings.defaultToRead
        set(value) {
            writeScope.launch {
                otherSettingsGateway.update { it.copy(defaultToRead = value) }
            }
        }

    override val preDownloadNum: Int get() = AppConfig.preDownloadNum
    override val changeSourceCheckAuthor: Boolean
        get() = changeSourceSettingsGateway.currentSettings.checkAuthor
}

/**
 * 宿主级界面设置端口实现：与完整模式共享全局 PreferKey.fontScale
 * （完整模式经 AppUiConfigurationGateway 读取同键），E-Ink「我的」页
 * 写入后由模块侧 recreate 入口 Activity 重应用。
 */
private object UiSettingsImpl : UiSettings {

    override var fontScaleSetting: Int?
        get() = AppConfigStore.getInt(PreferKey.fontScale)
        set(value) {
            if (value == null) {
                AppConfigStore.remove(PreferKey.fontScale)
            } else {
                AppConfigStore.putInt(PreferKey.fontScale, value)
            }
        }
}

/**
 * 封面加载端口实现（Coil 集成 + MD3 主工程 buildCoverImageRequest 同款
 * 请求选项）。封面 data 不做形态转换，url 原样交给单例 ImageLoader 的
 * CoverInterceptor / 内置 fetcher；仅 Wi-Fi 加载固定关闭（不设
 * LoadOnlyWifi extra 即为关闭）。
 */
private object CoverEngineImpl : CoverEngine {

    override val useDefaultCover: Boolean
        get() = AppConfig.useDefaultCover

    override fun coverRequestOptions(
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): ImageRequest.Builder.() -> Unit = {
        if (sourceOrigin != null) {
            extras[CoverExtras.SourceOrigin] = sourceOrigin
        }
        size(widthPx, heightPx)
    }
}
