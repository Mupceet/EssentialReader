package io.legado.app.eink.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * E-Ink stepped slider: a discrete "detent" progress bar with the current
 * value printed directly on the thumb.
 *
 * Interaction (zero motion — every change is an immediate state replacement):
 *  - Press anywhere on the track: the thumb inverts instantly (static press
 *    feedback, spec §35) and follows a horizontal drag, snapping to steps.
 *  - Tap (press + release without dragging): the value jumps to the tapped
 *    position.
 *  - A vertical-dominant gesture is rejected so a slider inside a scrollable
 *    column still lets the scroll win.
 *
 * Visuals follow the design system: flat track (filled segment + mid-gray
 * remainder + optional ruler ticks), solid black thumb with the value in
 * white, no ripple, no shadow, no animation.
 *
 * The value axis is integer steps of [valueRange]; callers map their real
 * units (dp, sp, floats) onto it and format [thumbLabel] for display.
 *
 * @param value Current step (coerced into [valueRange] when out of bounds)
 * @param onValueChange Invoked only when the step actually changes
 * @param valueRange Discrete step range, e.g. 0..64
 * @param modifier Modifier for the slider (sizing, weight)
 * @param enabled Whether the slider accepts input
 * @param thumbLabel Formats the value printed on the thumb
 * @param tickStep Ruler tick interval in steps; 0 draws no ticks
 */
@Composable
fun EInkSteppedSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: ClosedRange<Int>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbLabel: (Int) -> String = { it.toString() },
    tickStep: Int = 0,
) {
    val scheme = EInkTheme.colorScheme
    val start = valueRange.start
    val end = valueRange.endInclusive
    val steps = (end - start).coerceAtLeast(0)
    val safeValue = value.coerceIn(start, end)
    val density = LocalDensity.current
    val thumbWidthPx = with(density) { ThumbWidth.toPx() }

    var isPressed by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .heightIn(min = SliderHeight)
            .pointerInput(enabled, start, end) {
                if (!enabled || steps <= 0) return@pointerInput
                val touchSlop = viewConfiguration.touchSlop
                val thumbWidth = ThumbWidth.toPx()
                // x 坐标 → 档位:滑块中心对齐档位位置,两端档位时滑块贴边
                fun valueAt(xPx: Float): Int {
                    val travel = (size.width - thumbWidth).coerceAtLeast(0f)
                    if (travel <= 0f) return start
                    val fraction = ((xPx - thumbWidth / 2f) / travel).coerceIn(0f, 1f)
                    return start + (fraction * steps).roundToInt()
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    var isDragging = false
                    var rejected = false
                    var lastEmitted = valueAt(down.position.x)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) {
                                rejected = true
                                break
                            }
                            if (!change.pressed) break
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!isDragging) {
                                when {
                                    // 竖直滑动让位给父级滚动(如设置面板的 verticalScroll)
                                    abs(dy) > touchSlop && abs(dy) > abs(dx) -> {
                                        rejected = true
                                        break
                                    }
                                    abs(dx) > touchSlop -> isDragging = true
                                    else -> continue
                                }
                            }
                            change.consume()
                            val candidate = valueAt(change.position.x)
                            if (candidate != lastEmitted) {
                                lastEmitted = candidate
                                onValueChange(candidate)
                            }
                        }
                    } finally {
                        isPressed = false
                    }
                    // 按下后未拖动也未被父级消费 → 点按轨道,直接跳到点按档位
                    if (!isDragging && !rejected) {
                        val candidate = valueAt(down.position.x)
                        if (candidate != lastEmitted) {
                            onValueChange(candidate)
                        }
                    }
                }
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    safeValue.toFloat(),
                    start.toFloat()..end.toFloat(),
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 约束期即拿到最终宽度，首帧就能把滑块定位到目标档位，
        // 避免"先画在起点、下一帧再跳过去"的两帧跳动
        val trackWidthPx = if (maxWidth.isFinite) with(density) { maxWidth.toPx() } else 0f
        val stepPx = if (steps > 0) {
            (trackWidthPx - thumbWidthPx).coerceAtLeast(0f) / steps
        } else {
            0f
        }
        val thumbLeftPx = stepPx * (safeValue - start)

        val inactiveColor = scheme.disabledContent
        val fillColor = if (enabled) scheme.primary else inactiveColor
        Canvas(modifier = Modifier.matchParentSize()) {
            val y = size.height / 2f
            // 刻度尺:整条轨道静态绘制(被填充段和滑块覆盖处不可见),
            // 避免滑块移动时刻度出现/消失带来的额外刷新
            if (tickStep > 0 && steps > 0) {
                val halfTick = TickLength.toPx() / 2f
                val tickThickness = TickThickness.toPx()
                for (s in 0..steps) {
                    if (s % tickStep != 0) continue
                    val x = thumbWidthPx / 2f + stepPx * s
                    drawLine(
                        color = inactiveColor,
                        start = Offset(x, y - halfTick),
                        end = Offset(x, y + halfTick),
                        strokeWidth = tickThickness,
                        cap = StrokeCap.Square,
                    )
                }
            }
            drawLine(
                color = inactiveColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = EmptyTrackThickness.toPx(),
                cap = StrokeCap.Square,
            )
            drawLine(
                color = fillColor,
                start = Offset(0f, y),
                end = Offset(thumbWidthPx / 2f + thumbLeftPx, y),
                strokeWidth = FilledTrackThickness.toPx(),
                cap = StrokeCap.Square,
            )
        }

        // 按压反色:白底 + 1dp 边框 + 黑字;常态黑底白字
        val pressed = enabled && isPressed
        val thumbContainer = when {
            !enabled -> scheme.surface
            pressed -> scheme.surface
            else -> scheme.primary
        }
        val thumbContent = when {
            !enabled -> inactiveColor
            pressed -> scheme.onSurface
            else -> scheme.onPrimary
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbLeftPx.roundToInt(), 0) }
                .size(width = ThumbWidth, height = ThumbHeight)
                .background(color = thumbContainer, shape = EInkShapes.small)
                .then(
                    if (!enabled || pressed) {
                        Modifier.border(
                            width = 1.dp,
                            color = if (enabled) scheme.outline else inactiveColor,
                            shape = EInkShapes.small,
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            EInkText(
                text = thumbLabel(safeValue),
                color = thumbContent,
                style = EInkTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** 滑条触控目标高度(可点按元素 ≥ 48dp)。 */
private val SliderHeight = 48.dp

/** 滑块固定宽度:文本变化不引起宽度跳变,保证位置映射稳定。 */
private val ThumbWidth = 48.dp

private val ThumbHeight = 28.dp

private val FilledTrackThickness = 4.dp

private val EmptyTrackThickness = 2.dp

private val TickLength = 8.dp

private val TickThickness = 1.dp
