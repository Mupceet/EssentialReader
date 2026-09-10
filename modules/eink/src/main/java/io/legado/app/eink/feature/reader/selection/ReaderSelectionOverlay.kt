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
import androidx.compose.ui.geometry.Offset
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

/**
 * 选择浮条菜单动作（v2 三键）：
 * - [COPY] 复制：选文落剪贴板；
 * - [THOUGHT] 写想法：想法弹层，确认后 saveMarking(thought=true)；
 * - [DELETE] 删除：deleteMarking（点按场景携带 markingId，Task 6 接线；
 *   松手场景无 id，删除键置灰不可达）。
 */
enum class ReaderSelectionMenuAction { COPY, THOUGHT, DELETE }

/** 把手热区半径（dp，对齐宿主 28f*density）。 */
internal val SelectionHandleTouchRadiusDp = 28.dp

/**
 * 选区覆盖层：实线下划线预览（与落库后的划线渲染同形，所见即所得）+ 首末把手。
 *
 * 分两层绘制——
 * - 下划线预览（zIndex(-1f)）：垫在页画布**下方**，对选区 runs 逐行画基线下
 *   12% 行盒高的实线（主题 onBackground、1.5f·density），与画布正式装饰
 *   下划线同一公式与规格，拖拽调界随 runs 重建逐帧重绘；
 * - 把手 + 指针独占（zIndex(1f)）：压在正文上方。pointerInput 只在按下
 *   即命中把手时消费指针、独占本次拖拽；否则不消费任何事件直接返回，
 *   下层点按/翻页/长按检测器照常工作（空白处点击 = 清选区由 Screen 承担；
 *   浮条菜单以 zIndex(2f) 组合在本层之上，把手热区不吞菜单键点击）。
 *
 * 拖拽循环内经 rememberUpdatedState 读实时把手位/选区：不以 selection 为
 * pointerInput key——每次端点替换都会触发重组，以之为 key 会在拖拽中途
 * 重启、打断手势。端点替换语义幂等（只替换一端、另一端固定），重组滞后
 * 不产生累积误差。
 *
 * [handlesEnabled] = false（落库冻结）时把手转只读展示：选区与把手保持
 * 绘制，但不进入抓取、不消费任何事件，按下落回下层检测器（点按照常清
 * 选区）；冻结值同样经 rememberUpdatedState 实时读、不作 pointerInput
 * key——冻结发生在浮条展示期（无在途把手手势），以之为 key 徒增重启面。
 */
@Composable
internal fun ReaderSelectionOverlay(
    snapshot: ReaderPageSnapshot?,
    selection: ReaderSelectionUi?,
    handlesEnabled: Boolean,
    onSelectionChange: (ReaderSelectionUi?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot == null || selection == null) return
    val themeForeground = EInkTheme.colorScheme.onBackground
    val density = LocalDensity.current
    // 与画布正式装饰下划线同规格（1.5f·density，见 ReaderPageSnapshotCanvas）
    val underlineStrokePx = 1.5f * density.density
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
    val currentHandlesEnabled by rememberUpdatedState(handlesEnabled)

    // 实线下划线预览：垫在页画布下方，与落库后的划线渲染同形（见类 KDoc）。
    // y 取基线下 12% 行盒高——与画布正式装饰下划线同一公式，baseY 按行取自快照
    Canvas(modifier = modifier.zIndex(-1f)) {
        for (run in runs) {
            val line = snapshot.lines.getOrNull(run.lineIndex) ?: continue
            val y = line.baseY + (line.bottom - line.top) * 0.12f
            drawLine(
                color = themeForeground,
                start = Offset(run.left, y),
                end = Offset(run.right, y),
                strokeWidth = underlineStrokePx,
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
                    // 落库冻结：把手停用（只读展示），不进入抓取、不消费事件，
                    // 按下落回下层检测器（点按照常清选区，见类 KDoc）
                    if (!currentHandlesEnabled) return@awaitEachGesture
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
                        // sel 非空由本组合入口早退保证（selection == null 不组合），
                        // 无需判空；hit 真可空（拖出文本区）
                        if (hit != null) {
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
 * 选择浮条：横排动作键（复制/写想法/删除），锚在选区上方（放不下取下方），
 * 零动画直切。位置随选区把手锚点重算（把手拖拽期间浮条跟随重排）；x 跟随
 * 选区中心并钳制在画布内。写想法/删除为批注动作，按批注端口可用性与选区
 * 是否含标题行显隐（[showMarkingActions]——未注册端口或纯标题选区只留复制，
 * 长按选择与复制仍可用，不做假死路径）；删除键在标记落库确认并拿到
 * markingId 前禁用置灰（[deleteEnabled]——松手场景端口 saveMarking 只回
 * Boolean 无 id，恒为禁用；Task 6 点按场景经快照命中 run 携带 id 后启用）。
 * 按键取实心反白高对比形态（selected = true，titleMedium 16sp 加粗），
 * 不透明色块浮于正文之上，正文不透过按键（透明底会与正文视觉打架）。
 * 整体置于 zIndex(2f)：盖过把手独占层（zIndex(1f)），下方放置时菜单键
 * 落在把手 28dp 热区内也不被其 pointerInput 吞掉。
 *
 * @param canvasWidth 画布实测宽（调用方以 onSizeChanged 传入），浮条 x 钳制边界
 * @param deleteEnabled 删除键可用性（false = 置灰弱化、点击不响应）
 */
@Composable
internal fun ReaderSelectionMenu(
    anchorLeft: Float,
    anchorTop: Float,
    anchorRight: Float,
    anchorBottom: Float,
    canvasWidth: Float,
    showMarkingActions: Boolean,
    deleteEnabled: Boolean,
    onAction: (ReaderSelectionMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val itemWidthDp = 72.dp
    val itemCount = if (showMarkingActions) 3 else 1
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
        modifier = modifier
            // 压过把手独占层（zIndex(1f)）：下方放置（y = anchorBottom + gap）
            // 时菜单键落在把手热区内，无此层把手 pointerInput 会先于菜单消费点击
            .zIndex(2f)
            .offset { IntOffset(x.roundToInt(), y) },
    ) {
        EInkButton(
            text = "复制",
            selected = true,
            style = EInkTheme.typography.titleMedium,
            onClick = { onAction(ReaderSelectionMenuAction.COPY) },
            modifier = Modifier.width(itemWidthDp),
        )
        if (showMarkingActions) {
            EInkButton(
                text = "写想法",
                selected = true,
                style = EInkTheme.typography.titleMedium,
                onClick = { onAction(ReaderSelectionMenuAction.THOUGHT) },
                modifier = Modifier.width(itemWidthDp),
            )
            // 松手场景标记 id 不可得：置灰而非假装可用（点击无效果，见类 KDoc）
            EInkButton(
                text = "删除",
                selected = true,
                enabled = deleteEnabled,
                style = EInkTheme.typography.titleMedium,
                onClick = { onAction(ReaderSelectionMenuAction.DELETE) },
                modifier = Modifier.width(itemWidthDp),
            )
        }
    }
}
