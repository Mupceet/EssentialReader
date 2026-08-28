package io.legado.app.eink.bridge

import android.content.Context
import coil3.request.ImageRequest
import io.legado.app.eink.engine.CoverEngine
import io.legado.app.eink.engine.EInkEngineRegistry
import io.legado.app.eink.engine.GlobalSettings
import io.legado.app.eink.settings.EInkSettings
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coil.CoverExtras
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
 * 全局设置只读视图。
 *
 * threadCount/autoRefreshBook/preDownloadNum 走宿主 @Deprecated 同步门面
 * （"settings" DataStore 的 AppConfigStore 内存快照，主线程零 IO）；
 * changeSourceCheckAuthor 存在独立 local_ui_status DataStore，无同步
 * 门面，经 Koin 的 ChangeSourceSettingsGateway 读取。
 */
private object GlobalSettingsImpl : GlobalSettings, KoinComponent {

    private val changeSourceSettingsGateway:
        io.legado.app.domain.gateway.ChangeSourceSettingsGateway by inject()

    override val threadCount: Int get() = AppConfig.threadCount
    override val autoRefreshBook: Boolean get() = AppConfig.autoRefreshBook
    override val preDownloadNum: Int get() = AppConfig.preDownloadNum
    override val changeSourceCheckAuthor: Boolean
        get() = changeSourceSettingsGateway.currentSettings.checkAuthor
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
