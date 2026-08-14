package io.legado.app.eink.component

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState

/**
 * E-Ink 列表整页翻页（项对齐，无动画）。
 *
 * 与"固定滚动一屏高度"的区别：页边界始终对齐到列表项边界，
 * 满足两个不变量：
 *
 *  1. 任何时刻，页面第一项完整展示（不会出现只剩下半截的项）；
 *  2. 上一页底部未完整展示的项，就是下一页的第一项（完整展示）。
 *
 * 由此得到稳定的往返特性：翻下再翻上回到同一页，
 * 适合 E-Ink 的瞬时整页替换（无滚动动画、无中间态重绘）。
 */

/**
 * 下一页：底部被裁剪的第一项滚为下一页顶部（完整展示）；
 * 若当前页恰好无裁剪（整页刚好排满），则以最后一个可见项的下一项为起点。
 */
suspend fun LazyListState.pageDownEInk() {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return

    val viewportEnd = info.viewportEndOffset
    val firstPartial = items.firstOrNull { it.offset + it.size > viewportEnd }
    val target = firstPartial?.index
        ?: items.lastOrNull()?.takeIf { it.index < info.totalItemsCount - 1 }?.let { it.index + 1 }
        ?: return

    scrollToItem(target)
}

/**
 * 上一页：整屏上移后，若顶部项被裁剪则前滚对齐到该项边界，
 * 保证第一项完整展示；原页面第一项自然落在新页面中。
 */
suspend fun LazyListState.pageUpEInk() {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return

    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    scrollBy(-viewport)

    // 顶部项被裁掉上半部分 → 前滚补齐，恢复"第一项完整"不变量
    val first = layoutInfo.visibleItemsInfo.firstOrNull() ?: return
    val cut = (layoutInfo.viewportStartOffset - first.offset).toFloat()
    if (cut > 0f) scrollBy(cut)
}
