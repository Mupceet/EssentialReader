package io.legado.app.eink.contract

/**
 * 封面字节抓取端口：宿主图片/网络知识面向模块的唯一出口，签名不携带
 * 任何图片框架类型。
 *
 * 职责切分：
 *  - **宿主**：按 url + 书源 origin 抓取封面原始字节——防盗链/书源请求头、
 *    封面地址规则解析、图片解密、自有持久缓存全部在宿主管线完成；
 *  - **模块**：字节之后的解码、按目标尺寸降采样、显示与内存缓存（模块
 *    内部图片栈，不外泄到宿主编译期与依赖清单）。
 *
 * 封面数据流：
 * ```text
 * 封面组件（书架/详情/搜索，测得渲染尺寸 w×h + 书源 origin）
 *        │ http(s) 封面：fetchCoverBytes(url, origin)
 *        ▼
 * 宿主管线（防盗链头/地址规则解析/解密/持久缓存）──► ByteArray
 *        │ 模块自有 ImageLoader 的封面 Fetcher
 *        ▼
 * 解码 + 内存缓存（键含目标尺寸）──► 位图（失败走模块占位封面）
 *
 * 非 http(s) 封面（data: 内联/本地路径/content uri）不经本端口，
 * 由模块图片栈的内置 fetcher 直接解析——宿主实现无需处理。
 * ```
 *
 * 宿主实现义务：
 *  - **失败一律返回 null，不得抛异常**（CancellationException 透传）；
 *    模块把 null 视为加载失败，回退文字占位封面，不重试；
 *  - 建议自带持久缓存与失败冷却——无缓存的实现每次冷启动都重新抓取，
 *    坏源地址会反复触发网络失败（本仓参照实现复用完整模式的
 *    CoverFileCache 与 CoverFetcher 失败缓存）；
 *  - suspend 方法在调用方协程上下文执行，阻塞 IO 由实现自行调度；
 *  - 图片是否需要解密、请求头怎么带，宿主按自己的书源体系判定
 *    （[sourceOrigin] 为 null 表示本地书/无源封面，宿主直接裸抓即可）。
 */
interface CoverEngine {

    /**
     * 抓取封面字节。
     *
     * @param url 封面地址（http/https）。
     * @param sourceOrigin 书源 origin 标识（本地书/无源封面为 null）。
     * @return 封面原始字节（可能仍需解码，模块负责）；不可得返回 null。
     */
    suspend fun fetchCoverBytes(url: String, sourceOrigin: String?): ByteArray?
}
