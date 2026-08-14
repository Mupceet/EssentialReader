package io.legado.app.eink.modifier

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * E-Ink 分页手势。
 *
 * 列表禁用自由滚动（LazyColumn `userScrollEnabled = false`）后，
 * 用本 Modifier 把垂直滑动识别为整页翻页意图：
 *
 *  - 手指上滑超过 [PageSwipeThreshold] → [onPageDown]（下一页，等效 ▼）
 *  - 手指下滑超过阈值 → [onPageUp]（上一页，等效 ▲）
 *  - 未超过阈值视为误触，不动作（零动画、零中间态，避免 E-Ink 多次重绘）
 *
 * 位移不做实时跟随：E-Ink 上"手指拖到哪内容跟到哪"会产生大量局部刷新，
 * 且松手后的回弹/吸附在电泳屏上必然残影，因此采用阈值触发 + 整页跳转。
 */
fun Modifier.EInkPageSwipe(
    enabled: Boolean = true,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    var totalDrag = 0f
    val threshold = PageSwipeThreshold.toPx()
    detectVerticalDragGestures(
        onDragStart = { totalDrag = 0f },
        onVerticalDrag = { change, dragAmount ->
            totalDrag += dragAmount
            change.consume()
        },
        onDragEnd = {
            when {
                totalDrag <= -threshold -> onPageDown()
                totalDrag >= threshold -> onPageUp()
            }
            totalDrag = 0f
        },
        onDragCancel = { totalDrag = 0f }
    )
}

/** 触发翻页的最小滑动距离（低于此距离视为误触）。 */
private val PageSwipeThreshold = 48.dp
