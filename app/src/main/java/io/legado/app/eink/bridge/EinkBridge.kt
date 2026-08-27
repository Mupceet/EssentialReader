package io.legado.app.eink.bridge

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import com.bumptech.glide.RequestBuilder
import io.legado.app.eink.engine.CoverEngine
import io.legado.app.eink.engine.EinkEngineRegistry
import io.legado.app.eink.engine.GlobalSettings
import io.legado.app.eink.settings.EinkSettings
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isDataUrl
import java.io.File

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
object EinkBridge {

    fun install(context: Context) {
        EinkSettings.attach(context)
        EinkEngineRegistry.install(
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

/** 全局设置只读视图（转发 AppConfig）。 */
private object GlobalSettingsImpl : GlobalSettings {
    override val threadCount: Int get() = AppConfig.threadCount
    override val autoRefreshBook: Boolean get() = AppConfig.autoRefreshBook
    override val preDownloadNum: Int get() = AppConfig.preDownloadNum
    override val changeSourceCheckAuthor: Boolean get() = AppConfig.changeSourceCheckAuthor
}

/** 封面加载端口实现（Glide 集成 + View 版同款请求选项）。 */
private object CoverEngineImpl : CoverEngine {

    override val useDefaultCover: Boolean
        get() = AppConfig.useDefaultCover

    override fun resolveCoverModel(url: String): Any = when {
        url.isDataUrl() -> url
        url.isAbsUrl() -> url
        url.isContentScheme() -> Uri.parse(url)
        else -> kotlin.runCatching { File(url) }.getOrElse { url }
    }

    override fun coverRequestTransform(
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): (RequestBuilder<Drawable>) -> RequestBuilder<Drawable> = { request ->
        var builder = request.set(OkHttpModelLoader.loadOnlyWifiOption, false)
        if (sourceOrigin != null) {
            builder = builder.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        builder.override(widthPx, heightPx)
    }
}
