package io.legado.app.eink.contract

import coil3.request.ImageRequest

/**
 * 封面加载端口：宿主图片请求策略的唯一出口。
 *
 * 存在原因：封面的防盗链解析与请求头是**宿主知识**（书源 origin 与
 * 请求头的映射由宿主持有），模块图片栈无法自行构造。除本端口外，
 * 模块封面组件不感知任何宿主图片策略。
 *
 * 封面 data 不做形态转换：url 字符串原样交给图片加载器（http/data/
 * content/本地路径由宿主单例 ImageLoader 的拦截器或内置 fetcher 解析）。
 */
interface CoverEngine {

    /**
     * 构造封面请求选项：返回应用于模块图片请求构建器的配置块。
     *
     * 宿主实现义务：按 [sourceOrigin] 附加宿主拦截器识别的源信息
     * （据此解析最终 URL 与请求头），并把 [widthPx]/[heightPx] 设为
     * 请求目标尺寸（模块传入的是封面组件的实际渲染尺寸）。
     */
    fun coverRequestOptions(
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): ImageRequest.Builder.() -> Unit
}
