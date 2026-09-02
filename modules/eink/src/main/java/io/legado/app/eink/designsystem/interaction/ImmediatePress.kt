package io.legado.app.eink.designsystem.interaction

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/**
 * 快速点按时按压反色的最短保持时长（毫秒），规范 §35。
 *
 * E-Ink 局刷可见需要时间，快于该时长的点按也要保证反馈可被感知
 * （快速连点时反色不闪烁消失）。该数值由共享设施统一实现，
 * 组件与屏幕不得自行计时或更改。
 */
const val ImmediatePressMinHoldMillis: Long = 120L

/**
 * 即时按压状态。
 *
 * 为 E-Ink 按压反色反馈提供"按下立即置位、结束复位"的状态源，
 * 直接在指针手势层驱动，不经过 [androidx.compose.foundation.clickable]
 * 的交互派发通道 —— 后者在滚动容器内派发 Press/Release 会有延迟，
 * 手势被滚动消费时还可能滞留在按压态。
 *
 * 复位时机：
 *  - 所有指针抬起：若按住时长不足 [ImmediatePressMinHoldMillis]，
 *    补足最短时长再复位（快速点按的反色至少可见 120ms）；
 *  - 事件被滚动/父级消费（手指拖动列表）：立即复位，不做最短保持；
 *  - 手势取消。
 *
 * 用法：
 * ```
 * val press = rememberImmediatePressState()
 * Box(
 *     modifier = Modifier
 *         .then(press.modifier)
 *         .background(if (press.isPressed) inverted else normal)
 *         .einkClickable(onClick = onClick)
 * )
 * ```
 */
@Stable
class ImmediatePressState internal constructor(
    private val minHoldMillis: Long,
) {

    var isPressed by mutableStateOf(false)
        private set

    /** 挂到需要即时按压反馈的节点上（pointerInput 手势跟踪）。 */
    val modifier: Modifier = Modifier.pointerInput(minHoldMillis) {
        // 不用 awaitEachGesture：其块是受限挂起作用域（AwaitPointerEventScope），
        // 不能调用 delay() 做最短保持，故在 pointerInput 作用域内手动驱动手势循环
        while (true) {
            var released = false
            var heldMillis = 0L
            try {
                awaitPointerEventScope {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    while (true) {
                        val event: PointerEvent = awaitPointerEvent()
                        if (event.changes.all { !it.pressed }) {
                            released = true
                            heldMillis =
                                (event.changes.lastOrNull()?.uptimeMillis ?: down.uptimeMillis) -
                                        down.uptimeMillis
                            break
                        }
                        if (event.changes.any { it.isConsumed }) break
                    }
                }
                // 非受限作用域：正常抬指且不足最短保持时补足，保证反色可被感知
                if (released && heldMillis < minHoldMillis) {
                    delay(minHoldMillis - heldMillis)
                }
            } finally {
                isPressed = false
            }
            // 与 awaitEachGesture 语义对齐：所有指针抬起后才识别下一个手势
            // （消费退出路径手指可能仍按着，避免其被识别为新按压）
            awaitPointerEventScope {
                while (currentEvent.changes.any { it.pressed }) {
                    awaitPointerEvent()
                }
            }
        }
    }
}

/** 创建并记住 [ImmediatePressState]，默认最短反色保持 [ImmediatePressMinHoldMillis]。 */
@Composable
fun rememberImmediatePressState(
    minHoldMillis: Long = ImmediatePressMinHoldMillis,
): ImmediatePressState = remember { ImmediatePressState(minHoldMillis) }
