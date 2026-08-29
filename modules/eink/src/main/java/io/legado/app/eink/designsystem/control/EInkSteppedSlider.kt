package io.legado.app.eink.designsystem.control

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
import androidx.compose.ui.layout.layout
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * E-Ink stepped slider: a discrete "detent" progress bar with the current
 * value printed directly on the thumb.
 *
 * Interaction (zero motion — every change is an immediate state replacement):
 *  - Press on the track (outside the thumb): the value jumps instantly to the
 *    step at the pressed position (Material-slider semantics), then follows
 *    the drag from there.
 *  - Press on the thumb: no jump; the thumb follows a horizontal drag,
 *    snapping to steps.
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
 * Apply timing: by default [onValueChange] fires on every step change during
 * the drag (apply-as-you-drag). For deferred-apply settings (attach-time
 * config that needs a recreate to take effect), pass [onValueChangeFinished]:
 * keep a local preview state in [onValueChange] and commit once in
 * [onValueChangeFinished] when the gesture ends (finger lift).
 *
 * @param value Current step (coerced into [valueRange] when out of bounds)
 * @param onValueChange Invoked only when the step actually changes
 * @param valueRange Discrete step range, e.g. 0..64
 * @param modifier Modifier for the slider (sizing, weight)
 * @param enabled Whether the slider accepts input
 * @param thumbLabel Formats the value printed on the thumb
 * @param tickStep Ruler tick interval in steps; 0 draws no ticks
 * @param onValueChangeFinished Invoked once per gesture when the finger lifts
 *   after a non-rejected press/drag (also after a tap that didn't change the
 *   step); null keeps the apply-as-you-drag behavior
 * @param markerLabel 静态标识文本（如「默认」）：显示在滑条上方 [markerStep]
 *   档位对应的 x 位置，纯展示不可点；markerStep 为 null 时不显示
 * @param markerStep 标识对齐的档位；null 时不显示标识
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
    onValueChangeFinished: (() -> Unit)? = null,
    markerStep: Int? = null,
    markerLabel: String? = null,
) {
    val scheme = EInkTheme.colorScheme
    val start = valueRange.start
    val end = valueRange.endInclusive
    val steps = (end - start).coerceAtLeast(0)
    val safeValue = value.coerceIn(start, end)
    val density = LocalDensity.current
    val thumbWidthPx = with(density) { ThumbWidth.toPx() }

    var isPressed by remember { mutableStateOf(false) }

    // 命中判定需要实时值：pointerInput 不以 value 为 key（重挂会打断拖拽），
    // 闭包内的 value 快照会过期，经 rememberUpdatedState 读取最新值
    val currentValue by rememberUpdatedState(value)

    // 标识需要占用滑条上方的额外高度（标识行 + 间隙）
    val hasMarker = markerLabel != null && markerStep != null
    val minSliderHeight = if (hasMarker) SliderHeight + MarkerLabelSpace else SliderHeight

    BoxWithConstraints(
        modifier = modifier
            .heightIn(min = minSliderHeight)
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
                    // 按下分流：命中滑块 → 从当前值起拖；命中轨道 → 立即跳到
                    // 点按档位（Material 滑条同款；deferred 模式下该次变更由
                    // 抬手统一提交）。几何用 size 实时换算
                    val current = currentValue
                    val travel = (size.width - thumbWidth).coerceAtLeast(0f)
                    val stepWidth = if (steps > 0) travel / steps else 0f
                    val thumbLeft = stepWidth * (current - start)
                    val onThumb = down.position.x >= thumbLeft &&
                        down.position.x <= thumbLeft + thumbWidth
                    val downStep = valueAt(down.position.x)
                    var lastEmitted = current
                    if (!onThumb && downStep != current) {
                        lastEmitted = downStep
                        onValueChange(downStep)
                    }
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
                    // 抬手生效模式：手势有效结束后统一提交一次（rejected = 竖直
                    // 滚动抢占,视为未交互,不提交）
                    if (!rejected) {
                        onValueChangeFinished?.invoke()
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

        // 滑条上方的静态标识（如「默认」）：与 markerStep 档位中心对齐，
        // 几何复用轨道刻度的换算（thumbWidth/2 + stepPx × 档位偏移）
        if (hasMarker && steps > 0) {
            val markerCenterX = thumbWidthPx / 2f + stepPx * (markerStep!! - start)
            EInkText(
                text = markerLabel,
                style = EInkTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.place(
                                (markerCenterX - placeable.width / 2f).roundToInt(),
                                0
                            )
                        }
                    }
            )
        }
    }
}

/** 滑条触控目标高度(可点按元素 ≥ 48dp)。 */
private val SliderHeight = 48.dp

/** 滑条上方标识行（如「默认」）的预留高度（含与滑轨的间隙）。 */
private val MarkerLabelSpace = 24.dp

/** 滑块固定宽度:文本变化不引起宽度跳变,保证位置映射稳定。 */
private val ThumbWidth = 48.dp

private val ThumbHeight = 28.dp

private val FilledTrackThickness = 4.dp

private val EmptyTrackThickness = 2.dp

private val TickLength = 8.dp

private val TickThickness = 1.dp
