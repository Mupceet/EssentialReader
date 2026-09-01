package io.legado.app.eink.contract

import coil3.request.ImageRequest

/**
 * 封面加载端口：把宿主 Coil 集成（书源 origin 头解析、目标尺寸）
 * 挡在模块外。
 *
 * 封面 data 不做形态转换：url 字符串原样交给 Coil（http/data/content/
 * 本地路径均由宿主单例 ImageLoader 的拦截器或内置 fetcher 解析，
 * 与 MD3 主工程 buildCoverImageRequest 一致）。
 */
interface CoverEngine {

    /**
     * 封面请求选项：透传书源 origin（宿主拦截器据此解析最终 URL 与
     * 请求头）、目标尺寸等宿主侧策略。
     */
    fun coverRequestOptions(
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): ImageRequest.Builder.() -> Unit
}
