package io.legado.app.eink.modifier

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 即时按压状态。
 *
 * 为 E-Ink 按压反色反馈提供"按下立即置位、结束立即复位"的状态源，
 * 直接在指针手势层驱动，不经过 [androidx.compose.foundation.clickable]
 * 的交互派发通道 —— 后者在滚动容器内派发 Press/Release 会有延迟，
 * 手势被滚动消费时还可能滞留在按压态。
 *
 * 复位时机（任一）：
 *  - 所有指针抬起；
 *  - 事件被滚动/父级消费（手指拖动列表）；
 *  - 手势取消。
 *
 * 用法：
 * ```
 * val press = rememberImmediatePressState()
 * Box(
 *     modifier = Modifier
 *         .then(press.modifier)
 *         .background(if (press.isPressed) inverted else normal)
 *         .staticClickable(onClick = onClick)
 * )
 * ```
 */
@Stable
class ImmediatePressState internal constructor() {

    var isPressed by mutableStateOf(false)
        private set

    /** 挂到需要即时按压反馈的节点上（pointerInput 手势跟踪）。 */
    val modifier: Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            isPressed = true
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val allUp = event.changes.all { !it.pressed }
                    val anyConsumed = event.changes.any { it.isConsumed }
                    if (allUp || anyConsumed) break
                }
            } finally {
                isPressed = false
            }
        }
    }
}

/** 创建并记住 [ImmediatePressState]。 */
@Composable
fun rememberImmediatePressState(): ImmediatePressState = remember { ImmediatePressState() }
