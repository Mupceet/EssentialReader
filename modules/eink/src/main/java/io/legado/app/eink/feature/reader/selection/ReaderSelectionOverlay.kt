package io.legado.app.eink.feature.reader.selection

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.reader.applySpec
import kotlin.math.roundToInt

/** 选择浮条菜单动作。 */
enum class ReaderSelectionMenuAction { BOOKMARK, MARKING, COPY }

/** 把手热区半径（dp，对齐宿主 28f*density）。 */
internal val SelectionHandleTouchRadiusDp = 28.dp

/**
 * 选区覆盖层：逐行高亮带 + 首末把手。
 *
 * 分两层绘制——
 * - 高亮带（zIndex(-1f)）：垫在页画布**下方**，正文压在高亮上。取
 *   secondaryContainer 不透明实灰：E-Ink 禁 alpha 混灰（残影，规范 §1.3），
 *   而 surfaceVariant 在高对比灰阶板下与背景同值（纯白/纯黑）完全不可见；
 * - 把手 + 指针独占（zIndex(1f)）：压在正文上方。pointerInput 只在按下
 *   即命中把手时消费指针、独占本次拖拽；否则不消费任何事件直接返回，
 *   下层点按/翻页/长按检测器照常工作（空白处点击 = 清选区由 Screen 承担，
 *   浮条菜单由 ReaderScreen 在本覆盖层之上组合）。
 *
 * 拖拽循环内经 rememberUpdatedState 读实时把手位/选区：不以 selection 为
 * pointerInput key——每次端点替换都会触发重组，以之为 key 会在拖拽中途
 * 重启、打断手势。端点替换语义幂等（只替换一端、另一端固定），重组滞后
 * 不产生累积误差。
 */
@Composable
internal fun ReaderSelectionOverlay(
    snapshot: ReaderPageSnapshot?,
    selection: ReaderSelectionUi?,
    onSelectionChange: (ReaderSelectionUi?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot == null || selection == null) return
    val themeForeground = EInkTheme.colorScheme.onBackground
    val highlightArgb = EInkTheme.colorScheme.secondaryContainer
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 4.dp.toPx() }
    val touchRadiusPx = with(density) { SelectionHandleTouchRadiusDp.toPx() }

    // 与页画布同规格的测量闭包（applySpec 幂等，重复设置无害）
    val measureTitle = remember(snapshot.titleSpec, themeForeground) {
        val paint = Paint()
        val spec = snapshot.titleSpec
        { text: String ->
            paint.applySpec(spec, themeForeground.toArgb())
            paint.measureText(text)
        }
    }
    val measureContent = remember(snapshot.contentSpec, themeForeground) {
        val paint = Paint()
        val spec = snapshot.contentSpec
        { text: String ->
            paint.applySpec(spec, themeForeground.toArgb())
            paint.measureText(text)
        }
    }
    val runs = remember(selection, snapshot) {
        selectionRuns(snapshot, selection, measureTitle, measureContent)
    }
    val anchors = remember(runs) { handleAnchor(runs) }
    // 拖拽循环内读实时值：按下时刻的把手位/选区不冻结（见类 KDoc）
    val currentAnchors by rememberUpdatedState(anchors)
    val currentSelection by rememberUpdatedState(selection)

    // 高亮带：垫在页画布下方，正文压在高亮上（见类 KDoc）
    Canvas(modifier = modifier.zIndex(-1f)) {
        for (run in runs) {
            drawRoundRect(
                color = highlightArgb,
                topLeft = Offset(run.left, run.top),
                size = Size(run.right - run.left, run.bottom - run.top),
                cornerRadius = CornerRadius(4f, 4f),
            )
        }
    }
    // 把手 + 指针独占：按下即命中把手才消费指针，独占本次拖拽
    Canvas(
        modifier = modifier
            .zIndex(1f)
            .pointerInput(snapshot, measureTitle, measureContent) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val grab = grabHandle(currentAnchors, down.position, touchRadiusPx)
                        ?: return@awaitEachGesture
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // 把手上的裸点按就地消费，不外漏成「清选区」tap
                            change.consume()
                            break
                        }
                        change.consume()
                        val sel = currentSelection
                        val hit = hitTest(
                            snapshot, change.position.x, change.position.y, measureContent
                        )
                        if (sel != null && hit != null) {
                            onSelectionChange(moveEndpoint(snapshot, sel, grab, hit))
                        }
                    }
                }
            },
    ) {
        if (anchors != null) {
            val (startAnchor, endAnchor) = anchors
            drawHandle(startAnchor, handleRadiusPx, themeForeground)
            drawHandle(endAnchor, handleRadiusPx, themeForeground)
        }
    }
}

/** 把手：竖线 + 末端圆（宿主样式）。 */
private fun DrawScope.drawHandle(
    anchor: Pair<Float, Float>,
    radius: Float,
    color: Color,
) {
    val (x, top) = anchor
    drawLine(color, Offset(x, top), Offset(x, top + radius * 4f), strokeWidth = radius / 2f)
    drawCircle(color, radius = radius, center = Offset(x, top + radius * 4f))
}

/** 命中首/末把手；true = 起始把手，false = 末端把手，null = 未命中。 */
internal fun grabHandle(
    anchors: Pair<Pair<Float, Float>, Pair<Float, Float>>?,
    position: Offset,
    touchRadius: Float,
): Boolean? {
    if (anchors == null) return null
    val (startAnchor, endAnchor) = anchors
    val distanceToStart = Offset(startAnchor.first, startAnchor.second + touchRadius)
        .getDistanceTo(position)
    val distanceToEnd = Offset(endAnchor.first, endAnchor.second + touchRadius)
        .getDistanceTo(position)
    return when {
        distanceToStart <= touchRadius -> true
        distanceToEnd <= touchRadius -> false
        else -> null
    }
}

private fun Offset.getDistanceTo(other: Offset): Float = (this - other).getDistance()

/** 替换选区一端并重建（保持另一端不变）。 */
internal fun moveEndpoint(
    snapshot: ReaderPageSnapshot,
    selection: ReaderSelectionUi,
    isStart: Boolean,
    hit: ReaderTextHit,
): ReaderSelectionUi =
    buildSelection(
        snapshot,
        if (isStart) hit else selection.startHit,
        if (isStart) selection.endHit else hit,
    ) ?: selection

/**
 * 选择浮条：横排动作键，锚在选区上方（放不下取下方），零动画直切。
 * 位置随选区把手锚点重算（把手拖拽期间浮条跟随重排）；x 跟随选区中心
 * 并钳制在画布内。书签/笔记键按批注端口可用性显隐（降级后仅复制）。
 *
 * @param canvasWidth 画布实测宽（调用方以 onSizeChanged 传入），浮条 x 钳制边界
 */
@Composable
internal fun ReaderSelectionMenu(
    anchorLeft: Float,
    anchorTop: Float,
    anchorRight: Float,
    anchorBottom: Float,
    canvasWidth: Float,
    showBookmark: Boolean,
    showMarking: Boolean,
    onAction: (ReaderSelectionMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val itemWidthDp = 72.dp
    val itemCount = listOf(showBookmark, showMarking, true).count { it }
    val menuWidthPx = with(density) { (itemWidthDp * itemCount).toPx() }
    val menuHeightPx = with(density) { 48.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    // 位置：优先上方；x 跟随选区中心并钳制在画布内（画布极窄时上限取 0）
    val x = ((anchorLeft + anchorRight) / 2f - menuWidthPx / 2f)
        .coerceIn(0f, (canvasWidth - menuWidthPx).coerceAtLeast(0f))
    val y = if (anchorTop - menuHeightPx - gapPx >= 0f) {
        (anchorTop - menuHeightPx - gapPx).roundToInt()
    } else {
        (anchorBottom + gapPx).roundToInt()
    }
    Row(
        modifier = modifier.offset { IntOffset(x.roundToInt(), y) },
    ) {
        if (showBookmark) {
            EInkButton(
                text = "书签",
                onClick = { onAction(ReaderSelectionMenuAction.BOOKMARK) },
                modifier = Modifier.width(itemWidthDp),
            )
        }
        if (showMarking) {
            EInkButton(
                text = "笔记",
                onClick = { onAction(ReaderSelectionMenuAction.MARKING) },
                modifier = Modifier.width(itemWidthDp),
            )
        }
        EInkButton(
            text = "复制",
            onClick = { onAction(ReaderSelectionMenuAction.COPY) },
            modifier = Modifier.width(itemWidthDp),
        )
    }
}
