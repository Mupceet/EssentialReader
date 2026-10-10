package io.legado.app.eink.designsystem.pager

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * E-Ink 分页手势。
 *
 * 列表禁用自由滚动（LazyColumn `userScrollEnabled = false`）后，
 * 用本 Modifier 把拖动识别为整页动作意图：
 *
 *  - 手指上滑超过 [PageSwipeThreshold] → [onPageDown]（下一页，等效 ▼）
 *  - 手指下滑超过阈值 → [onPageUp]（上一页，等效 ▲）
 *  - 传入 [onSwipeLeft]/[onSwipeRight] 时，横向主导的滑动切走：
 *    左滑 → [onSwipeLeft]，右滑 → [onSwipeRight]（如目录页切 Tab）
 *  - 未超过阈值视为误触，不动作（零动画、零中间态，避免 E-Ink 多次重绘）
 *
 * 位移不做实时跟随：E-Ink 上"手指拖到哪内容跟到哪"会产生大量局部刷新，
 * 且松手后的回弹/吸附在电泳屏上必然残影，因此采用阈值触发 + 整页跳转。
 *
 * 回调经 [rememberUpdatedState] 持有：手势协程只在 [enabled] 变化或横滑
 * 有无变化时重启，期间调用方传入的新回调实例（如翻页 lambda 依赖最新总数）
 * 仍会被调用，不存在只捕获首次回调的过期闭包问题。
 */
@Composable
fun Modifier.EInkPageSwipe(
    enabled: Boolean = true,
    onPageUp: () -> Unit = {},
    onPageDown: () -> Unit = {},
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
): Modifier {
    val currentPageUp by rememberUpdatedState(onPageUp)
    val currentPageDown by rememberUpdatedState(onPageDown)
    val currentSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentSwipeRight by rememberUpdatedState(onSwipeRight)
    val hasHorizontalSwipe = onSwipeLeft != null || onSwipeRight != null
    return if (!hasHorizontalSwipe) {
        // 纯竖直路径（既有各分页列表）：行为与历史版本完全一致
        this.pointerInput(enabled) {
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
                        totalDrag <= -threshold -> currentPageDown()
                        totalDrag >= threshold -> currentPageUp()
                    }
                    totalDrag = 0f
                },
                onDragCancel = { totalDrag = 0f }
            )
        }
    } else {
        // 横竖统一仲裁路径：单一检测器按松手时累计位移定夺（见
        // [EInkSwipeArbitration]），杜绝两个轴向检测器抢 slop 的斜向误判
        this.pointerInput(enabled, hasHorizontalSwipe) {
            if (!enabled) return@pointerInput
            var totalX = 0f
            var totalY = 0f
            val threshold = PageSwipeThreshold.toPx()
            detectDragGestures(
                onDragStart = { totalX = 0f; totalY = 0f },
                onDrag = { change, dragAmount ->
                    totalX += dragAmount.x
                    totalY += dragAmount.y
                    change.consume()
                },
                onDragEnd = {
                    when (EInkSwipeArbitration.resolve(totalX, totalY, threshold)) {
                        EInkSwipeDirection.LEFT -> currentSwipeLeft?.invoke()
                        EInkSwipeDirection.RIGHT -> currentSwipeRight?.invoke()
                        EInkSwipeDirection.UP -> currentPageUp()
                        EInkSwipeDirection.DOWN -> currentPageDown()
                        EInkSwipeDirection.NONE -> Unit
                    }
                    totalX = 0f
                    totalY = 0f
                },
                onDragCancel = { totalX = 0f; totalY = 0f }
            )
        }
    }
}

/** 统一仲裁后的整页动作方向（NONE = 未过阈值，不动作）。 */
internal enum class EInkSwipeDirection { NONE, UP, DOWN, LEFT, RIGHT }

/**
 * 拖动结束的轴向仲裁（横滑切走 × 竖直翻页共存时的纯函数判据）。
 *
 * 为什么不用两个独立轴向检测器（detectHorizontalDragGestures ×
 * detectVerticalDragGestures）：二者按各自轴向先过触摸 slop 者得手，
 * 起手带下坠的横滑会被竖直检测器整笔抢走、翻页误触发（同阅读页
 * ReaderDragArbitration 修复前的症状）。改为单检测器累计两轴位移、
 * 松手时定夺：
 *  - 横向须**严格主导**（|Σx| ≥ threshold 且 |Σx| > |Σy|）才切走——
 *    翻页是分页列表的主操作，模糊的斜向滑动不应把它让给横滑；
 *  - 竖直保持纯竖直路径的宽判（只要 |Σy| ≥ threshold 即翻页，不比
 *    横向位移），斜向但竖直过阈值的滑动仍翻页，与既有行为一致。
 */
internal object EInkSwipeArbitration {

    /** [松手累计位移 x, y] 与触发阈值 → 唯一整页动作。 */
    fun resolve(totalX: Float, totalY: Float, threshold: Float): EInkSwipeDirection {
        if (abs(totalX) >= threshold && abs(totalX) > abs(totalY)) {
            return if (totalX < 0f) EInkSwipeDirection.LEFT else EInkSwipeDirection.RIGHT
        }
        return when {
            totalY <= -threshold -> EInkSwipeDirection.DOWN
            totalY >= threshold -> EInkSwipeDirection.UP
            else -> EInkSwipeDirection.NONE
        }
    }
}

/** 触发整页动作的最小滑动距离（低于此距离视为误触）。 */
private val PageSwipeThreshold = 48.dp
