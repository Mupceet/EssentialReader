package io.legado.app.eink.bridge

import coil3.request.ImageRequest
import io.legado.app.eink.contract.CoverEngine
import io.legado.app.help.coil.CoverExtras

/**
 * 封面加载端口实现（Coil 集成 + MD3 主工程 buildCoverImageRequest 同款
 * 请求选项）。封面 data 不做形态转换，url 原样交给单例 ImageLoader 的
 * CoverInterceptor / 内置 fetcher；仅 Wi-Fi 加载固定关闭（不设
 * LoadOnlyWifi extra 即为关闭）。
 *
 * 「总是使用默认封面」开关不在本端口（属 GlobalSettings），宿主侧实现
 * 见 EInkBridge.kt 的 GlobalSettingsImpl。
 */
internal object CoverEngineImpl : CoverEngine {

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
