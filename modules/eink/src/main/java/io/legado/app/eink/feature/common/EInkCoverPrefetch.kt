package io.legado.app.eink.feature.common

import android.content.Context
import coil3.SingletonImageLoader
import io.legado.app.eink.engine.EInkEngineRegistry

/**
 * 封面预取：把下一页的封面提前载入 Coil 内存缓存。
 *
 * 翻页是同步组合整页新条目：封面若不在内存缓存，Coil 请求即便最终命中
 * 磁盘也要经协程派发，先画文字占位、到位后再重画——墨水屏上两次全页
 * 绘制就是两次屏幕刷新。当前页落定后在后台预取下一页（[enqueue] 只负责
 * 填充缓存，无目标、无 UI 反馈），下次翻页 EInkAsyncImage 的同步命中
 * 快路径直接绘制缓存位图：零占位帧、单次绘制。
 *
 * 请求经 [buildEInkCoverRequest] 与显示路径共用（同一显式缓存键），预取
 * 写入的条目显示时必然同步命中。
 *
 * @param coverUrl 条目取封面地址（空跳过）
 * @param sourceOrigin 条目取书源 origin（书源请求头）
 */
fun <T> prefetchCovers(
    context: Context,
    items: List<T>,
    widthPx: Int,
    heightPx: Int,
    coverUrl: (T) -> String?,
    sourceOrigin: (T) -> String?,
) {
    if (items.isEmpty()) return
    // 「使用默认封面」模式下不显示网络封面，预取纯浪费
    if (EInkEngineRegistry.coverEngine.useDefaultCover) return
    val imageLoader = SingletonImageLoader.get(context)
    for (item in items) {
        val url = coverUrl(item)
        if (url.isNullOrBlank()) continue
        imageLoader.enqueue(
            buildEInkCoverRequest(context, url, sourceOrigin(item), widthPx, heightPx)
        )
    }
}
