package io.legado.app.eink.bridge

import coil3.request.ImageRequest
import io.legado.app.eink.contract.CoverEngine

/**
 * 封面加载端口实现（本宿主图片栈为 Glide，Coil 3 为模块自带依赖）。
 *
 * 宿主没有 Coil 单例 ImageLoader 与书源拦截器可供复用——本实现仅设置
 * 请求目标尺寸；防盗链/书源请求头无法附加，需要请求头的封面会加载失败
 * 并回退模块占位封面（既定取舍，见移植手册图片加载节）。若要恢复防盗链
 * 能力，宿主需为模块的 Coil 实例注册带书源头解析的网络拦截器。
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
        size(widthPx, heightPx)
    }
}
