package io.legado.app.eink.contract

import coil3.request.ImageRequest

/**
 * 封面加载端口：宿主图片请求策略的注入点。
 *
 * 部署前提（编译期即可见，随模块依赖 `api` 传递）：模块图片栈为
 * Coil 3，`coil-compose` 与 `coil-network-okhttp`（网络抓取器）已随
 * AAR/POM 传递——**无宿主接入也开箱可加载网络封面**。
 *
 * 封面请求流：
 * ```text
 * 封面组件（书架 / 详情 / 搜索，测量出渲染尺寸 w×h + 书源 origin）
 *        │ coverRequestOptions(origin, w, h)
 *        ▼
 * ImageRequest.Builder 配置块（宿主策略）
 *        │ 应用到模块的 Coil 请求
 *        ▼
 * Coil（OkHttp 网络抓取器）──宿主拦截器按 origin 解析防盗链/请求头
 *        └─► 位图（失败走模块占位封面）
 * ```
 *
 * 端口职责（宿主无对应知识时实现可为空配置块）：
 *  - **防盗链/书源请求头**：宿主知识（书源 origin 与请求头的映射由
 *    宿主持有）。Coil 宿主可复用宿主单例 ImageLoader 的拦截器链（如
 *    本仓的 CoverInterceptor）；Glide 等其它栈宿主默认无法表达——
 *    需要防盗链封面时为模块的 Coil 实例注册带书源解析的网络拦截器，
 *    否则相关封面回退占位（既定取舍）。
 *  - **目标尺寸**：把 [widthPx]/[heightPx] 设为请求目标尺寸（模块
 *    传入的是封面组件的实际渲染尺寸）。
 *
 * 封面 data 不做形态转换：url 字符串原样交给图片加载器（http/data/
 * content/本地路径由内置 fetcher 解析）。
 */
interface CoverEngine {

    /**
     * 构造封面请求选项：返回应用于模块图片请求构建器的配置块。
     *
     * @param sourceOrigin 书源 origin 标识（本地书/无源封面为 null）。
     * @param widthPx 封面组件渲染宽（px）。
     * @param heightPx 封面组件渲染高（px）。
     */
    fun coverRequestOptions(
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): ImageRequest.Builder.() -> Unit
}
